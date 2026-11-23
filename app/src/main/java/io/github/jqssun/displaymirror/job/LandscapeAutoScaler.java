package io.github.jqssun.displaymirror.job;

import android.opengl.GLES20;
import android.util.Log;
import io.github.jqssun.displaymirror.State;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class LandscapeAutoScaler {
  public final float[] landscapeMvpMatrix;
  private final float[] identityMvpMatrix;
  private final ExternalTextureRenderer externalTextureRenderer;
  private final int width;
  private final int height;
  private final int fbo;
  private int frameCounter;
  private boolean hasSymmetricBlackBar = false;
  private int topBottomBlackBarSize = 0;
  private int leftRightBlackBarSize = 0;
  private float currentScale = 1f;

  public LandscapeAutoScaler(
      ExternalTextureRenderer externalTextureRenderer, int width, int height, int fbo) {
    this.externalTextureRenderer = externalTextureRenderer;
    this.width = width;
    this.height = height;
    this.fbo = fbo;
    landscapeMvpMatrix = new float[16];
    android.opengl.Matrix.setIdentityM(landscapeMvpMatrix, 0);
    android.opengl.Matrix.scaleM(landscapeMvpMatrix, 0, 1, 1, 1.0f);

    identityMvpMatrix = new float[16];
    android.opengl.Matrix.setIdentityM(identityMvpMatrix, 0);
    android.opengl.Matrix.scaleM(identityMvpMatrix, 0, 1, 1, 1.0f);
  }

  public void onFrame() {
    if (frameCounter == 0) {
      adjustLandscapeMvpMatrix();
    }
    frameCounter = (frameCounter + 1) % 300;
  }

  private void adjustLandscapeMvpMatrix() {
    detectBlackBar();
    if (hasSymmetricBlackBar) {
      float scaleX = (float) (width) / (width - 2 * leftRightBlackBarSize);
      float scaleY = (float) (height) / (height - 2 * topBottomBlackBarSize);
      // Fill the frame: zoom enough to remove whichever symmetric bars were found.
      // Math.min() kept the bars because the axis without bars has scale 1.
      float scale = Math.max(scaleX, scaleY);

      android.opengl.Matrix.setIdentityM(landscapeMvpMatrix, 0);
      android.opengl.Matrix.scaleM(landscapeMvpMatrix, 0, scale, scale, 1.0f);

      android.util.Log.d(
          "LandscapeAutoScaler",
          String.format(
              "Applying scale transform: scaleX=%.2f, scaleY=%.2f, final scale=%.2f",
              scaleX, scaleY, scale));
      if (Math.abs(scale - currentScale) > 0.001f) {
        currentScale = scale;
        State.log(
            "Crop bars: left/right="
                + leftRightBlackBarSize
                + " top/bottom="
                + topBottomBlackBarSize
                + " zoom="
                + scale);
      }
    } else {
      // use identity matrix if no symmetric black bar
      for (int i = 0; i < identityMvpMatrix.length; i++) {
        landscapeMvpMatrix[i] = identityMvpMatrix[i];
      }
      if (currentScale != 1f) {
        currentScale = 1f;
        State.log("Crop bars: none, zoom reset");
      }
    }
  }

  private void detectBlackBar() {
    if (fbo != 0) {
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
    }

    externalTextureRenderer.renderFrame(identityMvpMatrix);

    GLES20.glFinish();

    ByteBuffer horizontalPixelBuffer = ByteBuffer.allocateDirect(width * 4);
    horizontalPixelBuffer.order(ByteOrder.nativeOrder());

    ByteBuffer verticalPixelBuffer = ByteBuffer.allocateDirect(height * 4);
    verticalPixelBuffer.order(ByteOrder.nativeOrder());
    int middleY = height / 2;
    GLES20.glReadPixels(
        0, middleY, width, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, horizontalPixelBuffer);

    int middleX = width / 2;
    GLES20.glReadPixels(
        middleX, 0, 1, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, verticalPixelBuffer);

    byte[] horizontalPixels = new byte[width * 4];
    byte[] verticalPixels = new byte[height * 4];
    horizontalPixelBuffer.get(horizontalPixels);
    verticalPixelBuffer.get(verticalPixels);

    int leftBlackWidth = 0;
    int rightBlackWidth = 0;
    int topBlackHeight = 0;
    int bottomBlackHeight = 0;

    for (int i = 0; i < width * 4; i += 4) {
      if (isBlackPixel(horizontalPixels[i], horizontalPixels[i + 1], horizontalPixels[i + 2])) {
        leftBlackWidth++;
      } else {
        break;
      }
    }

    for (int i = width * 4 - 4; i >= 0; i -= 4) {
      if (isBlackPixel(horizontalPixels[i], horizontalPixels[i + 1], horizontalPixels[i + 2])) {
        rightBlackWidth++;
      } else {
        break;
      }
    }

    // scan bottom black bar from bottom to top
    for (int i = 0; i < height * 4; i += 4) {
      if (isBlackPixel(verticalPixels[i], verticalPixels[i + 1], verticalPixels[i + 2])) {
        bottomBlackHeight++;
      } else {
        break;
      }
    }

    // scan top black bar from top to bottom
    for (int i = height * 4 - 4; i >= 0; i -= 4) {
      if (isBlackPixel(verticalPixels[i], verticalPixels[i + 1], verticalPixels[i + 2])) {
        topBlackHeight++;
      } else {
        break;
      }
    }

    // check if symmetric black bars exist
    boolean hasSymmetricHorizontalBars =
        Math.abs(leftBlackWidth - rightBlackWidth) <= 2
            && leftBlackWidth > 0
            && rightBlackWidth > 0;
    boolean hasSymmetricVerticalBars =
        Math.abs(topBlackHeight - bottomBlackHeight) <= 2
            && topBlackHeight > 0
            && bottomBlackHeight > 0;

    android.util.Log.d(
        "LandscapeAutoScaler",
        String.format(
            "Left bar: %d, right bar: %d, top bar: %d, bottom bar: %d, h-symmetric: %b, v-symmetric: %b",
            leftBlackWidth,
            rightBlackWidth,
            topBlackHeight,
            bottomBlackHeight,
            hasSymmetricHorizontalBars,
            hasSymmetricVerticalBars));

    int horizontalThreshold = (int) (width * 0.3);
    int verticalThreshold = (int) (height * 0.3);
    if (leftBlackWidth < horizontalThreshold
        && rightBlackWidth < horizontalThreshold
        && topBlackHeight < verticalThreshold
        && bottomBlackHeight < verticalThreshold) {
      // Side-only or top/bottom-only bars are valid. The old code required both axes,
      // so a tall phone screen mirrored into a landscape stream was never cropped.
      int sideBar = hasSymmetricHorizontalBars ? Math.min(leftBlackWidth, rightBlackWidth) : 0;
      int topBottomBar = hasSymmetricVerticalBars ? Math.min(topBlackHeight, bottomBlackHeight) : 0;
      if (sideBar == 0 && topBottomBar == 0) {
        hasSymmetricBlackBar = false;
        leftRightBlackBarSize = 0;
        topBottomBlackBarSize = 0;
      } else if (!hasSymmetricBlackBar
          || sideBar > leftRightBlackBarSize
          || topBottomBar > topBottomBlackBarSize) {
        // Only grow the crop to avoid zooming back and forth on small detection jitter.
        hasSymmetricBlackBar = true;
        leftRightBlackBarSize = sideBar;
        topBottomBlackBarSize = topBottomBar;
      }
    }
    // switch back to default framebuffer
    if (fbo != 0) {
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }
  }

  private boolean isBlackPixel(byte r, byte g, byte b) {
    return (r & 0xFF) == 0 && (g & 0xFF) == 0 && (b & 0xFF) == 0;
  }

  public void exitScale() {
    if (!hasSymmetricBlackBar) {
      return;
    }
    hasSymmetricBlackBar = false;
    currentScale = 1f;
    Log.d("LandscapeAutoScaler", "exitScale");
    // use identity matrix if no symmetric black bar
    for (int i = 0; i < identityMvpMatrix.length; i++) {
      landscapeMvpMatrix[i] = identityMvpMatrix[i];
    }
  }
}
