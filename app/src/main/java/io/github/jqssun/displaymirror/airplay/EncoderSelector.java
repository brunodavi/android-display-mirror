package io.github.jqssun.displaymirror.airplay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Pure encoder-selection logic (no Android imports) so it is unit-testable on the JVM.
// Candidates are populated from the public MediaCodecList APIs by EncoderDiscovery.
public final class EncoderSelector {

  public static final class Candidate {
    public final String name;
    public final boolean hardware;
    public final boolean surfaceInput;
    // vendor's VideoCapabilities.areSizeSupported for the requested size
    public final boolean sizeSupported;
    public final int minWidth;
    public final int maxWidth;
    public final int minHeight;
    public final int maxHeight;
    public final int widthAlignment;
    public final int heightAlignment;
    // supported frame-rate range for the requested size; 0..0 means unknown
    public final double minFps;
    public final double maxFps;

    public Candidate(
        String name,
        boolean hardware,
        boolean surfaceInput,
        boolean sizeSupported,
        int minWidth,
        int maxWidth,
        int minHeight,
        int maxHeight,
        int widthAlignment,
        int heightAlignment,
        double minFps,
        double maxFps) {
      this.name = name;
      this.hardware = hardware;
      this.surfaceInput = surfaceInput;
      this.sizeSupported = sizeSupported;
      this.minWidth = minWidth;
      this.maxWidth = maxWidth;
      this.minHeight = minHeight;
      this.maxHeight = maxHeight;
      this.widthAlignment = widthAlignment;
      this.heightAlignment = heightAlignment;
      this.minFps = minFps;
      this.maxFps = maxFps;
    }
  }

  private EncoderSelector() {}

  // null when the candidate can encode the requested format, otherwise the discard reason
  public static String rejectionReason(Candidate c, int width, int height, int fps) {
    if (!c.surfaceInput) {
      return "no Surface input (COLOR_FormatSurface)";
    }
    if (!c.sizeSupported) {
      return "vendor reports " + width + "x" + height + " unsupported";
    }
    if (width < c.minWidth || width > c.maxWidth || height < c.minHeight || height > c.maxHeight) {
      return "size out of supported range";
    }
    if (!isAligned(width, c.widthAlignment) || !isAligned(height, c.heightAlignment)) {
      return "dimensions not aligned to " + c.widthAlignment + "x" + c.heightAlignment;
    }
    if (c.maxFps > 0 && (fps < c.minFps || fps > c.maxFps)) {
      return "fps " + fps + " outside " + c.minFps + ".." + c.maxFps;
    }
    return null;
  }

  public static boolean isAligned(int value, int alignment) {
    return alignment <= 1 || value % alignment == 0;
  }

  // accepted candidates, hardware first; discovery order preserved within each group
  public static List<Candidate> order(List<Candidate> candidates, int width, int height, int fps) {
    List<Candidate> accepted = new ArrayList<>();
    for (Candidate c : candidates) {
      if (rejectionReason(c, width, height, fps) == null) {
        accepted.add(c);
      }
    }
    accepted.sort(Comparator.comparingInt((Candidate c) -> c.hardware ? 0 : 1));
    return accepted;
  }

  // aspect-preserving, alignment-respecting adaptation; only used when no candidate
  // supports the exact requested size. never crops or distorts beyond alignment rounding.
  public static int[] fitDimensions(int width, int height, Candidate c) {
    double scale =
        Math.min(1.0, Math.min((double) c.maxWidth / width, (double) c.maxHeight / height));
    int w = Math.max((int) Math.round(width * scale), c.minWidth);
    int h = Math.max((int) Math.round(height * scale), c.minHeight);
    w = align(w, c.widthAlignment);
    h = align(h, c.heightAlignment);
    w = Math.min(w, alignDown(c.maxWidth, c.widthAlignment));
    h = Math.min(h, alignDown(c.maxHeight, c.heightAlignment));
    return new int[] {Math.max(w, c.widthAlignment), Math.max(h, c.heightAlignment)};
  }

  static int align(int value, int alignment) {
    if (alignment <= 1) {
      return value;
    }
    return Math.max(alignment, ((value + alignment / 2) / alignment) * alignment);
  }

  static int alignDown(int value, int alignment) {
    if (alignment <= 1) {
      return value;
    }
    return Math.max(alignment, (value / alignment) * alignment);
  }

  // reusable diagnostic dump: every discovered encoder with capabilities and discard reason
  public static String describe(List<Candidate> candidates, int width, int height, int fps) {
    StringBuilder sb =
        new StringBuilder("AirPlay encoder candidates for " + width + "x" + height + "@" + fps + ":");
    if (candidates.isEmpty()) {
      sb.append(" none (no AVC encoder found)");
    }
    for (Candidate c : candidates) {
      String why = rejectionReason(c, width, height, fps);
      sb.append("\n  ")
          .append(c.name)
          .append(c.hardware ? " [hw]" : " [sw]")
          .append(" surface=")
          .append(c.surfaceInput)
          .append(" w=")
          .append(c.minWidth)
          .append("..")
          .append(c.maxWidth)
          .append("/")
          .append(c.widthAlignment)
          .append(" h=")
          .append(c.minHeight)
          .append("..")
          .append(c.maxHeight)
          .append("/")
          .append(c.heightAlignment)
          .append(" fps=")
          .append(c.minFps)
          .append("..")
          .append(c.maxFps)
          .append(why == null ? " -> OK" : " -> rejected: " + why);
    }
    return sb.toString();
  }
}
