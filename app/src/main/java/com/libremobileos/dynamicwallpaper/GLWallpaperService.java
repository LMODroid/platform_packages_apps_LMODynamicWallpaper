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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
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

    @Override
    public Engine onCreateEngine() {
        return new DynamicEngine();
    }

    private class DynamicEngine extends Engine {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Runnable slotCheck = this::updateSlot;

        private int slot = -1;
        private int width, height;
        /** The image of the current slot, cropped to the surface: the only full-size bitmap kept. */
        private Bitmap shown;
        /** The image faded out, only while a fade runs. */
        private Bitmap previous;
        private float alpha = 1f;

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
            handler.removeCallbacks(slotCheck);
            handler.postDelayed(slotCheck, 60000);
        }

        @Override
        public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            super.onSurfaceRedrawNeeded(holder);
            draw();
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            handler.removeCallbacksAndMessages(null);
            shown = previous = null;
        }

        private void updateSlot() {
            int s = currentSlot();
            if (s != slot && width > 0) {
                slot = s;
                previous = shown;
                shown = load(s);
                startFade();
            }
            handler.postDelayed(slotCheck, 60000);
        }

        private void startFade() {
            alpha = 0f;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    alpha = Math.min(1f, alpha + 0.2f);
                    if (alpha < 1f) {
                        handler.postDelayed(this, 1000 / 60);
                    } else {
                        previous = null;
                    }
                    draw();
                }
            });
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
