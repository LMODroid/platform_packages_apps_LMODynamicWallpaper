/*
 * Copyright (C) 2025 The LibreMobileOS Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.libremobileos.dynamicwallpaper;

import android.app.WallpaperColors;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

import java.util.Calendar;

/**
 * Shows one of four images by the time of day. The name stays: the default wallpaper
 * (config_default_wallpaper_component) and the wallpaper users picked point to this class.
 */
public class GLWallpaperService extends WallpaperService {

    /** The hour each image starts at, in order: morning, noon, evening, night (until morning). */
    static final int[] SLOT_START = {6, 11, 16, 20};
    static final int[] SLOT_IMAGE = {
            R.drawable.morning, R.drawable.noon, R.drawable.evening, R.drawable.night};

    /** The image for this hour of the day (0-23). */
    static int slotAt(int hour) {
        for (int i = SLOT_START.length - 1; i >= 0; i--) {
            if (hour >= SLOT_START[i]) return i;
        }
        return SLOT_START.length - 1;   // after midnight: still the night
    }

    static int currentSlot() {
        return slotAt(Calendar.getInstance().get(Calendar.HOUR_OF_DAY));
    }

    /** Milliseconds from now until the next image starts. */
    static long millisToNextSlot(Calendar now) {
        int hour = now.get(Calendar.HOUR_OF_DAY);
        int next = SLOT_START[(slotAt(hour) + 1) % SLOT_START.length];
        Calendar at = (Calendar) now.clone();
        at.set(Calendar.HOUR_OF_DAY, next);
        at.set(Calendar.MINUTE, 0);
        at.set(Calendar.SECOND, 0);
        at.set(Calendar.MILLISECOND, 0);
        if (!at.after(now)) at.add(Calendar.DAY_OF_MONTH, 1);
        return at.getTimeInMillis() - now.getTimeInMillis();
    }

    private static final long FADE_MS = 1200;

    @Override
    public Engine onCreateEngine() {
        return new DynamicEngine();
    }

    private class DynamicEngine extends Engine {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Runnable slotCheck = this::updateSlot;
        private final Runnable fadeFrame = this::fadeFrame;
        /** The time or the time zone changed: the image may be another one now. */
        private final BroadcastReceiver timeChanged = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateSlot();
            }
        };
        private boolean visible;
        private long fadeStart;
        /** The colors of slot colorsSlot, for Styles' wallpaper colors and SystemUI's text contrast. */
        private WallpaperColors colors;
        private int colorsSlot = -1;

        private int slot = -1;
        private int width, height;
        /** The image of the current slot, cropped to the surface: the only full-size bitmap kept. */
        private Bitmap shown;
        /** The image faded out, only while a fade runs. */
        private Bitmap previous;
        private float alpha = 1f;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_DATE_CHANGED);
            registerReceiver(timeChanged, filter, Context.RECEIVER_NOT_EXPORTED);
        }

        @Override
        public void onVisibilityChanged(boolean v) {
            visible = v;
            if (v) {
                updateSlot();   // the phone may have slept through the change (the handler's clock stops)
            } else if (previous != null) {
                endFade();
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            super.onSurfaceChanged(holder, format, w, h);
            if (w == width && h == height && shown != null) return;
            width = w;
            height = h;
            slot = currentSlot();
            shown = load(slot);
            previous = null;
            alpha = 1f;
            draw();
            scheduleNext();
        }

        @Override
        public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            super.onSurfaceRedrawNeeded(holder);
            draw();
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            unregisterReceiver(timeChanged);
            handler.removeCallbacksAndMessages(null);
            shown = previous = null;
        }

        private void updateSlot() {
            int s = currentSlot();
            if (s != slot && width > 0) {
                slot = s;
                notifyColorsChanged();   // SystemUI applies a theme change only once the screen is off
                Bitmap next = load(s);
                if (visible && shown != null) {
                    previous = shown;
                    shown = next;
                    fadeStart = SystemClock.uptimeMillis();
                    handler.removeCallbacks(fadeFrame);
                    handler.post(fadeFrame);
                } else {
                    shown = next;   // nobody sees it change: no fade
                    previous = null;
                    draw();
                }
            }
            scheduleNext();
        }

        private void scheduleNext() {
            handler.removeCallbacks(slotCheck);
            handler.postDelayed(slotCheck, millisToNextSlot(Calendar.getInstance()) + 1000);
        }

        private void fadeFrame() {
            float t = Math.min(1f, (SystemClock.uptimeMillis() - fadeStart) / (float) FADE_MS);
            alpha = t * t * (3 - 2 * t);   // ease in and out
            if (t < 1f) {
                draw();
                handler.postDelayed(fadeFrame, 16);
            } else {
                endFade();
            }
        }

        private void endFade() {
            handler.removeCallbacks(fadeFrame);
            previous = null;
            alpha = 1f;
            draw();
        }

        @Override
        public WallpaperColors onComputeColors() {
            int s = slot >= 0 ? slot : currentSlot();
            if (s != colorsSlot) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 16;   // ~160 px: plenty for the color extraction
                Bitmap small = BitmapFactory.decodeResource(getResources(), SLOT_IMAGE[s], o);
                colors = WallpaperColors.fromBitmap(small);
                small.recycle();
                colorsSlot = s;
            }
            return colors;
        }

        /** The slot's image decoded no larger than needed and cropped to the surface size. */
        private Bitmap load(int s) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeResource(getResources(), SLOT_IMAGE[s], o);
            float scale = Math.max((float) width / o.outWidth, (float) height / o.outHeight);
            int sample = 1;
            while (sample * 2 * scale <= 1f) sample *= 2;   // still at least as big as the crop
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap src = BitmapFactory.decodeResource(getResources(), SLOT_IMAGE[s], o);
            Bitmap out = centerCrop(src, width, height);
            if (out != src) src.recycle();
            return out;
        }

        private Bitmap centerCrop(Bitmap source, int w, int h) {
            float scale = Math.max((float) w / source.getWidth(), (float) h / source.getHeight());
            Matrix matrix = new Matrix();
            matrix.setScale(scale, scale);
            matrix.postTranslate((w - scale * source.getWidth()) / 2f, (h - scale * source.getHeight()) / 2f);
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            new Canvas(out).drawBitmap(source, matrix, new Paint(Paint.FILTER_BITMAP_FLAG));
            return out;
        }

        private void draw() {
            SurfaceHolder holder = getSurfaceHolder();
            if (shown == null || holder.getSurface() == null || !holder.getSurface().isValid()) return;
            Canvas canvas = holder.lockHardwareCanvas();
            if (canvas == null) return;
            try {
                if (previous != null) {
                    canvas.drawBitmap(previous, 0, 0, null);
                }
                paint.setAlpha(previous != null ? (int) (alpha * 255) : 255);
                canvas.drawBitmap(shown, 0, 0, paint);
            } finally {
                holder.unlockCanvasAndPost(canvas);
            }
        }
    }
}
