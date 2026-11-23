package io.github.jqssun.displaymirror.airplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

// Pure JVM tests for the selection logic. No MediaCodec simulation: physical encoder
// behavior can only be validated on a device.
public class EncoderSelectorTest {

  private static EncoderSelector.Candidate candidate(
      String name,
      boolean hardware,
      boolean surface,
      boolean sizeOk,
      int alignW,
      int alignH,
      double minFps,
      double maxFps) {
    return new EncoderSelector.Candidate(
        name, hardware, surface, sizeOk, 16, 4096, 16, 4096, alignW, alignH, minFps, maxFps);
  }

  private static EncoderSelector.Candidate hw(String name) {
    return candidate(name, true, true, true, 16, 16, 1, 120);
  }

  private static EncoderSelector.Candidate sw(String name) {
    return candidate(name, false, true, true, 16, 16, 1, 120);
  }

  private static List<String> names(List<EncoderSelector.Candidate> candidates) {
    List<String> out = new ArrayList<>();
    for (EncoderSelector.Candidate c : candidates) {
      out.add(c.name);
    }
    return out;
  }

  @Test
  public void rejectsEncoderWithoutSurfaceInput() {
    EncoderSelector.Candidate c = candidate("no.surface", true, false, true, 16, 16, 1, 120);
    assertNotNull(EncoderSelector.rejectionReason(c, 1280, 720, 30));
    assertTrue(EncoderSelector.order(Collections.singletonList(c), 1280, 720, 30).isEmpty());
  }

  @Test
  public void rejectsVendorUnsupportedSize() {
    EncoderSelector.Candidate c = candidate("vendor.no", true, true, false, 16, 16, 1, 120);
    String reason = EncoderSelector.rejectionReason(c, 1280, 720, 30);
    assertNotNull(reason);
    assertTrue(reason.contains("vendor"));
  }

  @Test
  public void rejectsSizeOutOfRange() {
    EncoderSelector.Candidate c =
        new EncoderSelector.Candidate(
            "small", true, true, true, 16, 640, 16, 480, 16, 16, 1, 120);
    assertNotNull(EncoderSelector.rejectionReason(c, 1280, 720, 30));
  }

  @Test
  public void rejectsUnalignedDimensions() {
    EncoderSelector.Candidate align32 = candidate("align32", true, true, true, 32, 32, 1, 120);
    // 720 is not a multiple of 32
    assertNotNull(EncoderSelector.rejectionReason(align32, 1280, 720, 30));
    EncoderSelector.Candidate align16 = candidate("align16", true, true, true, 16, 16, 1, 120);
    assertNull(EncoderSelector.rejectionReason(align16, 1280, 720, 30));
  }

  @Test
  public void rejectsFpsOutOfRange() {
    EncoderSelector.Candidate c = candidate("slow", true, true, true, 16, 16, 1, 25);
    assertNotNull(EncoderSelector.rejectionReason(c, 1280, 720, 30));
  }

  @Test
  public void unknownFpsRangeIsAccepted() {
    EncoderSelector.Candidate c = candidate("fps.unknown", true, true, true, 16, 16, 0, 0);
    assertNull(EncoderSelector.rejectionReason(c, 1280, 720, 30));
  }

  @Test
  public void acceptsStandard720p30() {
    assertNull(EncoderSelector.rejectionReason(hw("c2.exynos.avc.encoder"), 1280, 720, 30));
  }

  @Test
  public void hardwareFirstAndStableWithinGroups() {
    List<EncoderSelector.Candidate> all =
        Arrays.asList(
            sw("c2.android.avc.encoder"),
            hw("c2.exynos.avc.encoder"),
            sw("OMX.google.h264.encoder"),
            hw("OMX.Exynos.AVC.Encoder"));
    List<EncoderSelector.Candidate> ordered = EncoderSelector.order(all, 1280, 720, 30);
    assertEquals(
        Arrays.asList(
            "c2.exynos.avc.encoder",
            "OMX.Exynos.AVC.Encoder",
            "c2.android.avc.encoder",
            "OMX.google.h264.encoder"),
        names(ordered));
  }

  @Test
  public void orderKeepsAllAcceptedAsFallbackList() {
    List<EncoderSelector.Candidate> all =
        Arrays.asList(hw("a"), sw("b"), hw("c"), sw("d"));
    assertEquals(4, EncoderSelector.order(all, 1280, 720, 30).size());
  }

  @Test
  public void orderFiltersRejectedCandidates() {
    List<EncoderSelector.Candidate> all =
        Arrays.asList(
            hw("good"),
            candidate("no.surface", true, false, true, 16, 16, 1, 120),
            candidate("vendor.no", true, true, false, 16, 16, 1, 120),
            sw("good.sw"));
    assertEquals(Arrays.asList("good", "good.sw"), names(EncoderSelector.order(all, 1280, 720, 30)));
  }

  @Test
  public void emptyCandidatesProduceEmptyOrder() {
    assertTrue(
        EncoderSelector.order(
                Collections.<EncoderSelector.Candidate>emptyList(), 1280, 720, 30)
            .isEmpty());
  }

  @Test
  public void fitPreservesAspectAndAlignment() {
    EncoderSelector.Candidate c =
        new EncoderSelector.Candidate(
            "max1080", true, true, false, 16, 1920, 16, 1080, 16, 16, 1, 120);
    int[] fit = EncoderSelector.fitDimensions(2400, 1080, c);
    assertEquals(1920, fit[0]);
    assertEquals(864, fit[1]);
    double original = 2400.0 / 1080.0;
    double fitted = fit[0] / (double) fit[1];
    assertTrue(Math.abs(original - fitted) / original < 0.01);
  }

  @Test
  public void fitRoundsToNearestAlignment() {
    EncoderSelector.Candidate c = candidate("align16", true, true, true, 16, 16, 1, 120);
    int[] fit = EncoderSelector.fitDimensions(1281, 721, c);
    assertEquals(1280, fit[0]);
    assertEquals(720, fit[1]);
  }

  @Test
  public void fitNeverExceedsMaxAndStaysAligned() {
    EncoderSelector.Candidate c =
        new EncoderSelector.Candidate(
            "tiny", true, true, false, 16, 100, 16, 100, 32, 32, 1, 120);
    int[] fit = EncoderSelector.fitDimensions(1280, 720, c);
    assertTrue(fit[0] <= 100);
    assertTrue(fit[1] <= 100);
    assertEquals(0, fit[0] % 32);
    assertEquals(0, fit[1] % 32);
  }

  @Test
  public void fitDoesNotUpscale() {
    EncoderSelector.Candidate c = candidate("big", true, true, true, 16, 16, 1, 120);
    int[] fit = EncoderSelector.fitDimensions(1280, 720, c);
    assertEquals(1280, fit[0]);
    assertEquals(720, fit[1]);
  }

  @Test
  public void isAlignedHandlesTrivialAlignment() {
    assertTrue(EncoderSelector.isAligned(721, 1));
    assertTrue(EncoderSelector.isAligned(720, 16));
    assertFalse(EncoderSelector.isAligned(721, 16));
  }

  @Test
  public void describeListsCapabilitiesAndRejectionReasons() {
    String dump =
        EncoderSelector.describe(
            Arrays.asList(
                hw("ok.encoder"), candidate("bad.encoder", true, false, true, 16, 16, 1, 120)),
            1280,
            720,
            30);
    assertTrue(dump.contains("ok.encoder"));
    assertTrue(dump.contains("bad.encoder"));
    assertTrue(dump.contains("rejected"));
    assertTrue(dump.contains("Surface"));
    assertTrue(dump.contains("1280x720@30"));
  }

  @Test
  public void describeReportsNoEncoders() {
    String dump =
        EncoderSelector.describe(
            Collections.<EncoderSelector.Candidate>emptyList(), 1280, 720, 30);
    assertTrue(dump.contains("none"));
  }
}
