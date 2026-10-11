/*
 * Copied unchanged from Ki2 (https://github.com/valterc/ki2), except for the
 * R import. MIT License, Copyright (c) 2022 Valter C. - see LICENSE-ki2.txt
 */
package com.valterc.ki2.views.battery;

import android.graphics.Canvas;

public interface IBatteryViewRenderer {

    void updateSettings(BatteryView batteryView);

    void render(BatteryView batteryView, Canvas canvas, int internalWidth, int internalHeight);

}
