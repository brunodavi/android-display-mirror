package io.github.jqssun.displaymirror.airplay;

import android.content.Context;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;
import io.github.jqssun.displaymirror.Pref;
import io.github.jqssun.displaymirror.State;
import io.github.jqssun.displaymirror.job.CreateVirtualDisplay;
import io.github.jqssun.displaymirror.job.StreamRenderer;
import io.github.jqssun.displaymirror.job.VirtualDisplayArgs;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class AirPlayEncoder {
  private static final String TAG = "AirPlayEncoder";
  private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;

  private MediaCodec codec;
  private Surface inputSurface;
  private volatile VirtualDisplay virtualDisplay;
  private StreamRenderer pipeline;
  private volatile boolean running;
  private Thread encodeThread;
  private byte[] codecConfig;

  // diagnostics for the attempt that finally won
  private String chosenEncoder;
  private boolean chosenLowLatency;
  private MediaFormat chosenFormat;

  public int screenWidth;
  public int screenHeight;
  public int screenDpi;

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
      screenWidth = width;
      screenHeight = height;
      screenDpi = 160;

      boolean airplay1 = Pref.getAirPlay1Mode();
      boolean lowLatencySupportedByApi =
          android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R;
      List<String> encoders = _encoderNames();
      boolean codecReady = false;

      // Keep the original format as the first attempt. Some vendors (Exynos on Android 13)
      // reject KEY_LOW_LATENCY during configure() with an IllegalArgumentException whose
      // message is null. Retrying the same codec without that optional key keeps the
      // original behavior on devices where it works and avoids changing resolution/FPS.
      for (int i = 0; i < encoders.size() && !codecReady; i++) {
        String name = encoders.get(i);
        for (int variant = 0; variant < 2 && !codecReady; variant++) {
          boolean lowLatency = lowLatencySupportedByApi && variant == 0;
          if (!lowLatencySupportedByApi && variant == 1) continue;
          codecReady = _tryCodec(name, width, height, fps, airplay1, lowLatency);
        }
      }

      if (!codecReady) {
        State.log(
            "AirPlay encoder failed: no compatible AVC encoder for "
                + screenWidth
                + "x"
                + screenHeight
                + "@"
                + fps
                + " (see attempt logs above)");
        AirPlayState.clearTouchTarget();
        stop();
        return;
      }

      boolean rotate = Pref.getRotateWithContent();
      boolean crop = Pref.getCropBlackBorders();
      if (CreateVirtualDisplay.streamMirrors() && (rotate || crop)) {
        pipeline =
            new StreamRenderer(
                new VirtualDisplayArgs("AirPlay", screenWidth, screenHeight, fps, screenDpi, false),
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
      } else {
        virtualDisplay =
            CreateVirtualDisplay.createForStream(
                new VirtualDisplayArgs(
                    "AirPlay", screenWidth, screenHeight, fps, screenDpi, rotate),
                inputSurface);
        if (virtualDisplay == null) {
          State.log("AirPlay: failed to create virtual display");
          AirPlayState.clearTouchTarget();
          stop();
          return;
        }
        AirPlayState.setTouchTarget(virtualDisplay.getDisplay().getDisplayId(), inputSurface);
      }

      encodeThread = new Thread(this::_encodeLoop, "AirPlayEncode");
      encodeThread.start();
      State.log(
          "AirPlay encoder: "
              + screenWidth
              + "x"
              + screenHeight
              + " dpi="
              + screenDpi
              + " fps="
              + fps
              + " encoder="
              + chosenEncoder
              + (chosenLowLatency ? "" : " (low-latency disabled)"));
      State.log("AirPlay encoder format: " + chosenFormat);
    } catch (Exception e) {
      Log.e(TAG, "start failed", e);
      State.log("AirPlay encoder failed: " + _describe(e));
      AirPlayState.clearTouchTarget();
      stop();
    }
  }

  private boolean _tryCodec(
      String name, int width, int height, int fps, boolean airplay1, boolean lowLatency) {
    MediaCodec candidate = null;
    Surface surface = null;
    MediaFormat format = null;
    String stage = "createByCodecName";
    try {
      format = _buildFormat(width, height, fps, airplay1, lowLatency);
      candidate = MediaCodec.createByCodecName(name);
      stage = "configure";
      candidate.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      stage = "createInputSurface";
      surface = candidate.createInputSurface();
      stage = "start";
      candidate.start();

      codec = candidate;
      inputSurface = surface;
      chosenEncoder = name;
      chosenLowLatency = lowLatency;
      chosenFormat = format;
      // ownership transferred to the encoder instance
      candidate = null;
      surface = null;
      return true;
    } catch (Exception e) {
      String msg =
          "AirPlay encoder attempt failed: encoder="
              + name
              + " "
              + width
              + "x"
              + height
              + "@"
              + fps
              + " lowLatency="
              + lowLatency
              + " stage="
              + stage
              + " format="
              + format
              + " -> "
              + _describe(e);
      Log.e(TAG, msg, e);
      State.log(msg);
      return false;
    } finally {
      _release(candidate, surface, false);
    }
  }

  private static MediaFormat _buildFormat(
      int width, int height, int fps, boolean airplay1, boolean lowLatency) {
    MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
    format.setInteger(MediaFormat.KEY_BIT_RATE, airplay1 ? 4_000_000 : 8_000_000);
    format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
    format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, airplay1 ? 1 : 3);
    format.setInteger(
        MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
    if (lowLatency) {
      format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
    }
    format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
    if (airplay1) {
      // baseline@4.0 is the only profile/level combo that clears
      // every known third-party decoder quirk. See AIRPIN.md.
      format.setInteger(
          MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
      format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4);
    }
    return format;
  }

  // Default codec first, then other Surface-capable AVC encoders (hardware before software).
  private static List<String> _encoderNames() {
    List<String> names = new ArrayList<String>();
    MediaCodec probe = null;
    try {
      probe = MediaCodec.createEncoderByType(MIME);
      String defaultName = probe.getName();
      if (defaultName != null) names.add(defaultName);
    } catch (Exception e) {
      Log.w(TAG, "default AVC encoder unavailable: " + _describe(e));
    } finally {
      if (probe != null) {
        try {
          probe.release();
        } catch (Exception ignored) {
        }
      }
    }

    List<String> hardware = new ArrayList<String>();
    List<String> software = new ArrayList<String>();
    try {
      MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
      MediaCodecInfo[] infos = list.getCodecInfos();
      for (int i = 0; i < infos.length; i++) {
        MediaCodecInfo info = infos[i];
        if (!info.isEncoder()) continue;
        String[] types = info.getSupportedTypes();
        boolean avc = false;
        for (int t = 0; t < types.length; t++) {
          if (MIME.equalsIgnoreCase(types[t])) {
            avc = true;
            break;
          }
        }
        if (!avc || !_hasSurfaceInput(info)) continue;
        String name = info.getName();
        boolean hw = true;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
          try {
            hw = info.isHardwareAccelerated();
          } catch (Exception ignored) {
          }
        }
        List<String> target = hw ? hardware : software;
        if (!target.contains(name) && !names.contains(name)) target.add(name);
      }
    } catch (Exception e) {
      Log.w(TAG, "encoder discovery failed: " + _describe(e));
    }
    names.addAll(hardware);
    names.addAll(software);
    return names;
  }

  private static boolean _hasSurfaceInput(MediaCodecInfo info) {
    try {
      MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(MIME);
      int[] colors = caps.colorFormats;
      for (int i = 0; i < colors.length; i++) {
        if (colors[i] == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) return true;
      }
    } catch (Exception ignored) {
    }
    return false;
  }

  private static void _release(MediaCodec c, Surface s, boolean started) {
    if (c != null && started) {
      try {
        c.stop();
      } catch (Exception ignored) {
      }
    }
    if (s != null) {
      try {
        s.release();
      } catch (Exception ignored) {
      }
    }
    if (c != null) {
      try {
        c.release();
      } catch (Exception ignored) {
      }
    }
  }

  private static String _describe(Throwable e) {
    if (e == null) return "<none>";
    String message = e.getMessage();
    StringBuilder sb = new StringBuilder();
    sb.append(e.getClass().getName()).append(": ");
    sb.append(message == null || message.length() == 0 ? "<null>" : message);
    try {
      StringWriter sw = new StringWriter();
      e.printStackTrace(new PrintWriter(sw));
      String[] lines = sw.toString().split("\\r?\\n");
      int count = Math.min(6, lines.length);
      for (int i = 1; i < count; i++) {
        sb.append(" | ").append(lines[i].trim());
      }
    } catch (Throwable ignored) {
    }
    return sb.toString();
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
    _release(codec, inputSurface, true);
    codec = null;
    inputSurface = null;
    chosenEncoder = null;
    chosenLowLatency = false;
    chosenFormat = null;
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
