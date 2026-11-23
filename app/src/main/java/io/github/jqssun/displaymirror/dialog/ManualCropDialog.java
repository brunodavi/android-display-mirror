package io.github.jqssun.displaymirror.dialog;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import io.github.jqssun.displaymirror.R;
import io.github.jqssun.displaymirror.TvFocus;

public class ManualCropDialog {
  public interface OnChangeListener {
    // values: left, top, right, bottom, zoomX %, zoomY %
    void onChange(int[] values, boolean enabled);
  }

  private static final int BORDER_STEP = 8;
  private static final int ZOOM_STEP = 5;
  private static final int BORDER_MIN = -4096;
  private static final int BORDER_MAX = 4096;
  private static final int ZOOM_MIN = 50;
  private static final int ZOOM_MAX = 400;

  public static void show(
      Context context, int[] initial, boolean initialEnabled, OnChangeListener listener) {
    View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_manual_crop, null);

    int[] initialValues = initial == null ? new int[6] : initial.clone();
    final int[] values = new int[6];
    System.arraycopy(initialValues, 0, values, 0, Math.min(initialValues.length, 6));
    if (initialValues.length < 6) {
      values[4] = 100;
      values[5] = 100;
    }
    for (int i = 0; i < 4; i++) {
      values[i] = _clamp(values[i], BORDER_MIN, BORDER_MAX);
    }
    values[4] = _clamp(values[4], ZOOM_MIN, ZOOM_MAX);
    values[5] = _clamp(values[5], ZOOM_MIN, ZOOM_MAX);
    final int[] originalValues = values.clone();
    final boolean originalEnabled = initialEnabled;

    final TextInputEditText[] fields =
        new TextInputEditText[] {
          dialogView.findViewById(R.id.crop_left_value),
          dialogView.findViewById(R.id.crop_top_value),
          dialogView.findViewById(R.id.crop_right_value),
          dialogView.findViewById(R.id.crop_bottom_value),
          dialogView.findViewById(R.id.crop_zoom_x_value),
          dialogView.findViewById(R.id.crop_zoom_y_value)
        };
    final boolean[] updating = {false};
    final boolean[] enabled = {initialEnabled};

    _writeFields(fields, values, updating);

    for (int i = 0; i < fields.length; i++) {
      final int index = i;
      fields[i].addTextChangedListener(
          new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
              if (updating[0]) {
                return;
              }
              String text = s.toString().trim();
              if (text.length() == 0 || text.equals("-") || text.equals("+")) {
                return;
              }
              try {
                int parsed = Integer.parseInt(text);
                int clamped = _clampFor(parsed, index);
                if (clamped != parsed) {
                  updating[0] = true;
                  fields[index].setText(String.valueOf(clamped));
                  fields[index].setSelection(fields[index].length());
                  updating[0] = false;
                }
                values[index] = clamped;
                enabled[0] = true;
                listener.onChange(values.clone(), true);
              } catch (NumberFormatException ignored) {
                // keep the last valid value while the user is typing
              }
            }
          });
    }

    _bindStepper(
        dialogView, R.id.crop_left_minus, 0, -BORDER_STEP, fields, values, updating, enabled, listener);
    _bindStepper(
        dialogView, R.id.crop_left_plus, 0, BORDER_STEP, fields, values, updating, enabled, listener);
    _bindStepper(
        dialogView, R.id.crop_top_minus, 1, -BORDER_STEP, fields, values, updating, enabled, listener);
    _bindStepper(
        dialogView, R.id.crop_top_plus, 1, BORDER_STEP, fields, values, updating, enabled, listener);
    _bindStepper(
        dialogView, R.id.crop_right_minus, 2, -BORDER_STEP, fields, values, updating, enabled, listener);
    _bindStepper(
        dialogView, R.id.crop_right_plus, 2, BORDER_STEP, fields, values, updating, enabled, listener);
    _bindStepper(
        dialogView, R.id.crop_bottom_minus, 3, -BORDER_STEP, fields, values, updating, enabled, listener);
    _bindStepper(
        dialogView, R.id.crop_bottom_plus, 3, BORDER_STEP, fields, values, updating, enabled, listener);
    _bindStepper(
        dialogView, R.id.crop_zoom_x_minus, 4, -ZOOM_STEP, fields, values, updating, enabled, listener);
    _bindStepper(
        dialogView, R.id.crop_zoom_x_plus, 4, ZOOM_STEP, fields, values, updating, enabled, listener);
    _bindStepper(
        dialogView, R.id.crop_zoom_y_minus, 5, -ZOOM_STEP, fields, values, updating, enabled, listener);
    _bindStepper(
        dialogView, R.id.crop_zoom_y_plus, 5, ZOOM_STEP, fields, values, updating, enabled, listener);

    final int[] defaults = {0, 0, 0, 0, 100, 100};
    TvFocus.attach(
        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.airplay_manual_crop_title)
            .setView(dialogView)
            .setNeutralButton(
                R.string.reset,
                (dialog, which) -> {
                  System.arraycopy(defaults, 0, values, 0, defaults.length);
                  _writeFields(fields, values, updating);
                  enabled[0] = false;
                  listener.onChange(values.clone(), false);
                })
            .setNegativeButton(
                R.string.cancel,
                (dialog, which) -> listener.onChange(originalValues.clone(), originalEnabled))
            .setPositiveButton(
                R.string.done, (dialog, which) -> listener.onChange(values.clone(), enabled[0]))
            .show());
  }

  private static void _writeFields(TextInputEditText[] fields, int[] values, boolean[] updating) {
    updating[0] = true;
    for (int i = 0; i < fields.length; i++) {
      fields[i].setText(String.valueOf(values[i]));
    }
    updating[0] = false;
  }

  private static void _bindStepper(
      View root,
      int buttonId,
      final int index,
      final int step,
      final TextInputEditText[] fields,
      final int[] values,
      final boolean[] updating,
      final boolean[] enabled,
      final OnChangeListener listener) {
    MaterialButton button = root.findViewById(buttonId);
    button.setOnClickListener(
        v -> {
          values[index] = _clampFor(values[index] + step, index);
          updating[0] = true;
          fields[index].setText(String.valueOf(values[index]));
          fields[index].setSelection(fields[index].length());
          updating[0] = false;
          enabled[0] = true;
          listener.onChange(values.clone(), true);
        });
  }

  private static int _clampFor(int value, int index) {
    if (index >= 4) {
      return _clamp(value, ZOOM_MIN, ZOOM_MAX);
    }
    return _clamp(value, BORDER_MIN, BORDER_MAX);
  }

  private static int _clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
