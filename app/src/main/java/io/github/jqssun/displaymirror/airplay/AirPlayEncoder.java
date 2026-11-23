package io.github.jqssun.displaymirror.airplay;

import android.content.Context;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;
import io.github.jqssun.displaymirror.Pref;
import io.github.jqssun.displaymirror.State;
import io.github.jqssun.displaymirror.job.CreateVirtualDisplay;
import io.github.jqssun.displaymirror.job.StreamRenderer;
import io.github.jqssun.displaymirror.job.VirtualDisplayArgs;
import java.nio.ByteBuffer;
import java.util.List;

public class AirPlayEncoder {
  private static final String TAG = "AirPlayEncoder";
  private MediaCodec codec;
  private volatile VirtualDisplay virtualDisplay;
  private StreamRenderer pipeline;
  private volatile boolean running;
  private Thread encodeThread;
  private byte[] codecConfig;

  public int screenWidth;
  public int screenHeight;
  public int screenDpi;

  public boolean isRunning() {
    return running;
  }

  public void start(int width, int height, int fps) {
    if (running) return;
    running = true;

    try {
      if (width <= 0 || height <= 0) {
        Context ctx = State.getContext();
        if (ctx == null) {
          running = false;
          return;
        }
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE))
            .getDefaultDisplay()
            .getRealMetrics(dm);
        width = dm.widthPixels;
        height = dm.heightPixels;
      }
      screenDpi = 160;

      // dynamic discovery: no fixed encoder names, capabilities come from the device
      List<EncoderSelector.Candidate> candidates = EncoderDiscovery.discover(width, height);
      State.log(EncoderSelector.describe(candidates, width, height, fps));
      List<EncoderSelector.Candidate> ordered =
          EncoderSelector.order(candidates, width, height, fps);

      int encWidth = width;
      int encHeight = height;
      if (ordered.isEmpty()) {
        // no encoder supports the exact negotiated size: adapt respecting alignment and
        // aspect ratio, and say so loudly (never silently change the stream dimensions)
        for (EncoderSelector.Candidate c : candidates) {
          if (!c.surfaceInput) {
            continue;
          }
          int[] fit = EncoderSelector.fitDimensions(width, height, c);
          List<EncoderSelector.Candidate> fitCandidates =
              EncoderDiscovery.discover(fit[0], fit[1]);
          List<EncoderSelector.Candidate> fitOrdered =
              EncoderSelector.order(fitCandidates, fit[0], fit[1], fps);
          if (!fitOrdered.isEmpty()) {
            ordered = fitOrdered;
            encWidth = fit[0];
            encHeight = fit[1];
            State.log(
                "AirPlay encoder: no encoder supports "
                    + width
                    + "x"
                    + height
                    + "; adapting to "
                    + encWidth
                    + "x"
                    + encHeight
                    + " (aspect preserved, aligned)");
            break;
          }
        }
      }
      screenWidth = encWidth;
      screenHeight = encHeight;

      if (ordered.isEmpty()) {
        State.log(
            "AirPlay encoder failed: no compatible AVC encoder for "
                + encWidth
                + "x"
                + encHeight
                + "@"
                + fps
                + " (see candidate list above)");
        AirPlayState.clearTouchTarget();
        running = false;
        return;
      }

      boolean airplay1 = Pref.getAirPlay1Mode();
      boolean rotate = Pref.getRotateWithContent();
      boolean crop = Pref.getCropBlackBorders();
      // keep the projection so display creation can be retried with the next candidate
      // (createForStream consumes State's projection slot)
      MediaProjection projection = State.getMediaProjection();
      Exception lastError = null;

      for (EncoderSelector.Candidate c : ordered) {
        boolean codecStarted = false;
        // format variants: 0 = full (unchanged behavior on working devices),
        // 1 = drop the decoder-only low-latency key, 2 = also drop max-bframes.
        // every attempt uses a fresh codec instance; failed surfaces are never reused.
        for (int variant = 0; variant < 3 && !codecStarted; variant++) {
          MediaFormat format = buildFormat(encWidth, encHeight, fps, airplay1, variant);
          MediaCodec mc = null;
          String stage = "createByCodecName";
          try {
            mc = MediaCodec.createByCodecName(c.name);
            stage = "configure";
            mc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            stage = "createInputSurface";
            Surface inputSurface = mc.createInputSurface();
            stage = "start";
            mc.start();
            codecStarted = true;
            codec = mc;
            mc = null;

            stage = "createVirtualDisplay";
            boolean attached;
            if (CreateVirtualDisplay.streamMirrors() && (rotate || crop)) {
              pipeline =
                  new StreamRenderer(
                      new VirtualDisplayArgs(
                          "AirPlay", encWidth, encHeight, fps, screenDpi, false),
                      rotate,
                      crop,
                      inputSurface);
              pipeline.start(
                  (input, w, h) -> {
                    virtualDisplay =
                        CreateVirtualDisplay.createForStream(
                            new VirtualDisplayArgs("AirPlay", w, h, fps, screenDpi, false), input);
                    if (virtualDisplay == null) {
                      State.log("AirPlay: failed to create virtual display");
                      AirPlayState.clearTouchTarget();
                    } else {
                      AirPlayState.setTouchTarget(
                          virtualDisplay.getDisplay().getDisplayId(), virtualDisplay.getSurface());
                    }
                    return virtualDisplay;
                  });
              // display creation continues on the render thread, as before
              attached = true;
            } else {
              if (State.getMediaProjection() == null && projection != null) {
                State.setMediaProjection(projection);
              }
              virtualDisplay =
                  CreateVirtualDisplay.createForStream(
                      new VirtualDisplayArgs(
                          "AirPlay", encWidth, encHeight, fps, screenDpi, rotate),
                      inputSurface);
              if (virtualDisplay == null) {
                State.log("AirPlay: failed to create virtual display (encoder=" + c.name + ")");
                attached = false;
              } else {
                AirPlayState.setTouchTarget(
                    virtualDisplay.getDisplay().getDisplayId(), inputSurface);
                attached = true;
              }
            }

            if (attached) {
              encodeThread = new Thread(this::_encodeLoop, "AirPlayEncode");
              encodeThread.start();
              State.log(
                  "AirPlay encoder: "
                      + encWidth
                      + "x"
                      + encHeight
                      + " dpi="
                      + screenDpi
                      + " fps="
                      + fps
                      + " encoder="
                      + c.name
                      + (variant > 0 ? " (format fallback v" + variant + ")" : ""));
              State.log("AirPlay encoder format: " + format);
              return;
            }
            // display failed with this codec: release it and move to the next candidate;
            // other format variants would not change the display outcome
            releaseCodec();
            break;
          } catch (Exception e) {
            lastError = e;
            State.log(
                "AirPlay encoder attempt failed: encoder="
                    + c.name
                    + " stage="
                    + stage
                    + " variant="
                    + variant
                    + " -> "
                    + e.getClass().getName()
                    + ": "
                    + e.getMessage());
            State.log(Log.getStackTraceString(e));
            Log.e(TAG, "attempt failed: " + c.name + " stage=" + stage + " variant=" + variant, e);
            release(mc);
            releaseCodec();
            releaseDisplay();
            if (pipeline != null) {
              pipeline.stop();
              pipeline = null;
            }
          }
        }
      }

      State.log(
          "AirPlay encoder failed: all "
              + ordered.size()
              + " candidate encoder(s) exhausted"
              + (lastError != null
                  ? "; last error: "
                      + lastError.getClass().getName()
                      + ": "
                      + lastError.getMessage()
                  : ""));
      AirPlayState.clearTouchTarget();
      running = false;
    } catch (Exception e) {
      Log.e(TAG, "start failed", e);
      State.log("AirPlay encoder failed: " + e.getClass().getName() + ": " + e.getMessage());
      State.log(Log.getStackTraceString(e));
      AirPlayState.clearTouchTarget();
      running = false;
    }
  }

  private static MediaFormat buildFormat(
      int width, int height, int fps, boolean airplay1, int variant) {
    MediaFormat format =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
    format.setInteger(MediaFormat.KEY_BIT_RATE, airplay1 ? 4_000_000 : 8_000_000);
    format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
    format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, airplay1 ? 1 : 3);
    format.setInteger(
        MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
    // KEY_LOW_LATENCY is documented for decoders; some encoders reject it at configure()
    if (variant < 1 && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
      format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
    }
    if (variant < 2) {
      format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
    }
    if (airplay1) {
      // baseline@4.0 is the only profile/level combo that clears
      // every known third-party decoder quirk. See AIRPIN.md.
      format.setInteger(
          MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
      format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4);
    }
    return format;
  }

  private static void release(MediaCodec mc) {
    if (mc == null) {
      return;
    }
    try {
      mc.stop();
    } catch (Exception ignored) {
    }
    try {
      mc.release();
    } catch (Exception ignored) {
    }
  }

  private void releaseCodec() {
    release(codec);
    codec = null;
  }

  private void releaseDisplay() {
    if (virtualDisplay != null) {
      try {
        virtualDisplay.release();
      } catch (Exception ignored) {
      }
      virtualDisplay = null;
    }
  }

  public void stop() {
    running = false;
    if (pipeline != null) {
      pipeline.stop();
      pipeline = null;
    }
    if (virtualDisplay != null) {
      virtualDisplay.release();
      virtualDisplay = null;
    }
    AirPlayState.clearTouchTarget();
    if (codec != null) {
      try {
        codec.stop();
      } catch (Exception ignored) {
      }
      try {
        codec.release();
      } catch (Exception ignored) {
      }
      codec = null;
    }
    if (encodeThread != null) {
      encodeThread.interrupt();
      encodeThread = null;
    }
    codecConfig = null;
  }

  private void _encodeLoop() {
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    while (running) {
      try {
        int idx = codec.dequeueOutputBuffer(info, 100_000);
        if (idx < 0) continue;

        ByteBuffer buf = codec.getOutputBuffer(idx);
        if (buf == null) {
          codec.releaseOutputBuffer(idx, false);
          continue;
        }

        byte[] data = new byte[info.size];
        buf.position(info.offset);
        buf.get(data, 0, info.size);

        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
          codecConfig = data;
        } else {
          boolean isKey = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
          if (isKey && codecConfig != null) {
            byte[] combined = new byte[codecConfig.length + data.length];
            System.arraycopy(codecConfig, 0, combined, 0, codecConfig.length);
            System.arraycopy(data, 0, combined, codecConfig.length, data.length);
            AirPlayService.onNativeVideoFrame(combined, true);
          } else {
            AirPlayService.onNativeVideoFrame(data, isKey);
          }
        }

        codec.releaseOutputBuffer(idx, false);
      } catch (Exception e) {
        if (running) Log.e(TAG, "encode error", e);
      }
    }
  }
}
