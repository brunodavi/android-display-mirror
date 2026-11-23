package io.github.jqssun.displaymirror.airplay;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.util.Range;
import io.github.jqssun.displaymirror.job.AndroidVersions;
import java.util.ArrayList;
import java.util.List;

// Populates EncoderSelector.Candidate from the public MediaCodecList APIs.
// No fixed encoder names: everything comes from what the device actually reports.
public final class EncoderDiscovery {
  private static final String TAG = "EncoderDiscovery";

  private EncoderDiscovery() {}

  public static List<EncoderSelector.Candidate> discover(int width, int height) {
    List<EncoderSelector.Candidate> out = new ArrayList<>();
    MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
    for (MediaCodecInfo info : list.getCodecInfos()) {
      if (!info.isEncoder()) {
        continue;
      }
      boolean avc = false;
      for (String type : info.getSupportedTypes()) {
        if (type.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AVC)) {
          avc = true;
          break;
        }
      }
      if (!avc) {
        continue;
      }
      try {
        out.add(toCandidate(info, width, height));
      } catch (Exception e) {
        // buggy vendor codec info: skip it, but leave a trace
        Log.w(TAG, "skipping " + info.getName() + ": " + e);
      }
    }
    return out;
  }

  private static EncoderSelector.Candidate toCandidate(
      MediaCodecInfo info, int width, int height) {
    MediaCodecInfo.CodecCapabilities caps =
        info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
    boolean surface = false;
    for (int colorFormat : caps.colorFormats) {
      if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) {
        surface = true;
        break;
      }
    }
    int minW = 0;
    int maxW = Integer.MAX_VALUE;
    int minH = 0;
    int maxH = Integer.MAX_VALUE;
    int alignW = 1;
    int alignH = 1;
    boolean sizeSupported = true;
    double minFps = 0;
    double maxFps = 0;
    MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();
    if (vc != null) {
      // each query is guarded: some vendor implementations throw on unusual sizes
      try {
        sizeSupported = vc.areSizeSupported(width, height);
      } catch (Exception ignored) {
      }
      try {
        Range<Integer> w = vc.getSupportedWidths();
        Range<Integer> h = vc.getSupportedHeights();
        minW = w.getLower();
        maxW = w.getUpper();
        minH = h.getLower();
        maxH = h.getUpper();
        alignW = vc.getWidthAlignment();
        alignH = vc.getHeightAlignment();
      } catch (Exception ignored) {
      }
      try {
        Range<Double> rates = vc.getSupportedFrameRatesFor(width, height);
        minFps = rates.getLower();
        maxFps = rates.getUpper();
      } catch (Exception ignored) {
      }
    }
    return new EncoderSelector.Candidate(
        info.getName(),
        isHardware(info),
        surface,
        sizeSupported,
        minW,
        maxW,
        minH,
        maxH,
        alignW,
        alignH,
        minFps,
        maxFps);
  }

  // isHardware() exists since API 29; below that, classify by the well-known AOSP
  // software codec prefixes (classification only, never used to exclude candidates)
  static boolean isHardware(MediaCodecInfo info) {
    if (Build.VERSION.SDK_INT >= AndroidVersions.API_29_ANDROID_10) {
      try {
        return info.isHardware();
      } catch (Exception ignored) {
      }
    }
    String name = info.getName().toLowerCase();
    return !(name.startsWith("omx.google.") || name.startsWith("c2.android."));
  }
}
