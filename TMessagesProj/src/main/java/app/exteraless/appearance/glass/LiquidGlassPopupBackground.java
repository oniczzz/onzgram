package app.exteraless.appearance.glass;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.Theme;

/**
 * Фон всплывающего меню ({@link org.telegram.ui.ActionBar.ActionBarPopupWindow}) в стиле
 * Liquid Glass. Один экземпляр на окно меню.
 *
 * <ul>
 *     <li>API 31+: задний план (окно активности под меню) копируется через {@link PixelCopy}
 *     в уменьшенный битмап, кладётся в {@link RenderNode} с
 *     {@link RenderEffect#createBlurEffect}; на API 33+ поверх размытия — AGSL-преломление
 *     краёв ({@link RenderEffect#createChainEffect}). Копия делается один раз и повторяется,
 *     только если меню сдвинулось за пределы снятой области, — на кадр это одна отрисовка
 *     готового узла.</li>
 *     <li>API 26–30: полупрозрачный фон и тонкая обводка, без размытия.</li>
 *     <li>Ниже 26 (нет {@code clipOutPath} для тени): вызывающий рисует обычный фон.</li>
 * </ul>
 * Сверху всегда кладётся слой {@link LiquidGlassController#drawOverlay}.
 */
public final class LiquidGlassPopupBackground {

    private final View host;
    private final Path path = new Path();
    private final RectF shape = new RectF();
    private final RectF tmpRect = new RectF();
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** {@link Backdrop}, типизирован как Object, чтобы класс не грузился на старых API. */
    private Object backdrop;

    public LiquidGlassPopupBackground(@NonNull View host) {
        this.host = host;
    }

    public static boolean isActive() {
        return Build.VERSION.SDK_INT >= 26 && LiquidGlassController.isEnabled();
    }

    /**
     * Рисует фон меню.
     *
     * @param bounds   границы фона вместе с отступами под тень (как у 9-patch фона)
     * @param paddings отступы от границ до самой формы меню
     * @param color    цвет фона меню
     * @param alpha    прозрачность 0..255 (анимация появления)
     * @return false — Liquid Glass выключен или недоступен, рисуй обычный фон
     */
    public boolean draw(@NonNull Canvas canvas, @NonNull Rect bounds, @NonNull Rect paddings, int color, int alpha,
                        @Nullable Theme.ResourcesProvider resourcesProvider) {
        if (!isActive()) {
            return false;
        }
        shape.set(bounds.left + paddings.left, bounds.top + paddings.top,
            bounds.right - paddings.right, bounds.bottom - paddings.bottom);
        if (shape.width() <= 0 || shape.height() <= 0) {
            return true;
        }
        final float radius = Math.min(dp(LiquidGlassController.POPUP_RADIUS_DP), Math.min(shape.width(), shape.height()) / 2f);
        path.rewind();
        path.addRoundRect(shape, radius, radius, Path.Direction.CW);

        final float a = Math.max(0, Math.min(255, alpha)) / 255f;
        final float intensity = LiquidGlassController.getIntensity();

        drawShadow(canvas, radius, a);

        int state = Backdrop.NONE;
        if (Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated()) {
            if (backdrop == null) {
                backdrop = new Backdrop(host);
            }
            state = ((Backdrop) backdrop).draw(canvas, path, shape, radius, intensity, a);
        }

        final float tintAlpha;
        if (state == Backdrop.DRAWN) {
            // прозрачное стекло: за меню видно размытый и преломлённый фон
            tintAlpha = lerp(0.72f, 0.36f, intensity);
        } else if (state == Backdrop.PENDING) {
            // копия заднего плана ещё не пришла — почти непрозрачно, чтобы не мигать
            tintAlpha = 0.97f;
        } else {
            tintAlpha = lerp(0.97f, 0.84f, intensity);
        }
        tintPaint.setColor(ColorUtils.setAlphaComponent(color, (int) (Color.alpha(color) * tintAlpha * a)));
        canvas.drawPath(path, tintPaint);

        final boolean refractive = state == Backdrop.DRAWN
            && LiquidGlassController.getTier() == LiquidGlassController.TIER_SHADER;
        LiquidGlassController.drawOverlay(canvas, path, shape, radius, a, refractive, resourcesProvider);
        return true;
    }

    /** Мягкая тень вокруг формы; внутрь не попадает, иначе просвечивала бы сквозь стекло. */
    @RequiresApi(26)
    private void drawShadow(Canvas canvas, float radius, float alpha) {
        canvas.save();
        canvas.clipOutPath(path);
        final float offsetY = dpf2(1.5f);
        for (int i = 1; i <= 4; i++) {
            final float outset = dpf2(i);
            tmpRect.set(shape);
            tmpRect.inset(-outset, -outset);
            tmpRect.offset(0, offsetY);
            shadowPaint.setColor(Color.argb((int) (16 * alpha * (5 - i) / 4f), 0, 0, 0));
            canvas.drawRoundRect(tmpRect, radius + outset, radius + outset, shadowPaint);
        }
        canvas.restore();
    }

    /** Освобождает копию заднего плана (меню закрыто). */
    public void release() {
        if (Build.VERSION.SDK_INT >= 31 && backdrop != null) {
            ((Backdrop) backdrop).release();
        }
        backdrop = null;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    @RequiresApi(31)
    private static final class Backdrop {

        static final int NONE = 0;
        static final int PENDING = 1;
        static final int DRAWN = 2;

        private static final int DOWNSCALE = 4;
        private static final int MAX_FAILED_COPIES = 3;

        private final View host;
        private final RenderNode node = new RenderNode("onzgramLiquidGlass");
        private final int[] hostLocation = new int[2];
        private final int[] decorLocation = new int[2];
        private final Rect captured = new Rect();
        private final Rect visible = new Rect();

        private Bitmap bitmap;
        private boolean nodeDirty;
        private boolean requestInFlight;
        private boolean released;
        private int failedCopies;

        private boolean effectValid;
        private float effectBlur, effectLeft, effectTop, effectRight, effectBottom, effectRadius, effectThickness, effectStrength;
        private RuntimeShader refraction;
        private boolean refractionFailed;

        Backdrop(View host) {
            this.host = host;
        }

        int draw(Canvas canvas, Path path, RectF shape, float radius, float intensity, float alpha) {
            if (released || failedCopies >= MAX_FAILED_COPIES) {
                return NONE;
            }
            final Activity activity = AndroidUtilities.findActivity(host.getContext());
            final Window window = activity != null ? activity.getWindow() : null;
            final View decor = window != null ? window.peekDecorView() : null;
            if (decor == null || !decor.isAttachedToWindow() || decor.getWidth() <= 0 || decor.getHeight() <= 0) {
                return NONE;
            }
            // Только меню в своём окне поверх окна активности: внутри самой активности копия
            // захватила бы меню, а меню над диалогом / шторкой получило бы чужой задний план.
            final View root = host.getRootView();
            if (root == decor) {
                return NONE;
            }
            final ViewGroup.LayoutParams lp = root.getLayoutParams();
            if (!(lp instanceof WindowManager.LayoutParams) || ((WindowManager.LayoutParams) lp).token != decor.getWindowToken()) {
                return NONE;
            }

            host.getLocationOnScreen(hostLocation);
            decor.getLocationOnScreen(decorLocation);
            final int offsetX = hostLocation[0] - decorLocation[0];
            final int offsetY = hostLocation[1] - decorLocation[1];
            visible.set(offsetX, offsetY, offsetX + host.getWidth(), offsetY + host.getHeight());
            if (!visible.intersect(0, 0, decor.getWidth(), decor.getHeight())) {
                return NONE;
            }
            if (bitmap == null || !captured.contains(visible)) {
                requestCopy(window, decor, visible);
                if (bitmap == null) {
                    return PENDING;
                }
            }

            final int bitmapWidth = bitmap.getWidth();
            final int bitmapHeight = bitmap.getHeight();
            if (nodeDirty) {
                node.setPosition(0, 0, bitmapWidth, bitmapHeight);
                final RecordingCanvas recordingCanvas = node.beginRecording(bitmapWidth, bitmapHeight);
                try {
                    recordingCanvas.drawBitmap(bitmap, 0, 0, null);
                } finally {
                    node.endRecording();
                }
                nodeDirty = false;
                effectValid = false;
            }

            final float scaleX = captured.width() / (float) bitmapWidth;
            final float scaleY = captured.height() / (float) bitmapHeight;
            // начало снятой области в координатах меню
            final float originX = captured.left - offsetX;
            final float originY = captured.top - offsetY;

            updateEffect(
                Math.max(0.5f, LiquidGlassController.getBlurRadius() / scaleX),
                (shape.left - originX) / scaleX,
                (shape.top - originY) / scaleY,
                (shape.right - originX) / scaleX,
                (shape.bottom - originY) / scaleY,
                radius / scaleX,
                Math.min(LiquidGlassController.getLensThickness(), Math.min(shape.width(), shape.height()) / 4f) / scaleX,
                LiquidGlassController.getRefractionIntensity());
            node.setAlpha(Math.max(0f, Math.min(1f, alpha)));

            canvas.save();
            canvas.clipPath(path);
            canvas.translate(originX, originY);
            canvas.scale(scaleX, scaleY);
            canvas.drawRenderNode(node);
            canvas.restore();
            return DRAWN;
        }

        private void updateEffect(float blur, float left, float top, float right, float bottom, float radius,
                                  float thickness, float strength) {
            if (effectValid
                && Math.abs(effectBlur - blur) < 0.25f
                && Math.abs(effectLeft - left) < 0.25f && Math.abs(effectTop - top) < 0.25f
                && Math.abs(effectRight - right) < 0.25f && Math.abs(effectBottom - bottom) < 0.25f
                && Math.abs(effectRadius - radius) < 0.25f && Math.abs(effectThickness - thickness) < 0.25f
                && Math.abs(effectStrength - strength) < 0.01f) {
                return;
            }
            effectValid = true;
            effectBlur = blur;
            effectLeft = left;
            effectTop = top;
            effectRight = right;
            effectBottom = bottom;
            effectRadius = radius;
            effectThickness = thickness;
            effectStrength = strength;

            final RenderEffect blurEffect = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP);
            RenderEffect effect = blurEffect;
            if (Build.VERSION.SDK_INT >= 33 && LiquidGlassController.getTier() == LiquidGlassController.TIER_SHADER) {
                final RenderEffect refractionEffect = createRefraction(left, top, right, bottom, radius, thickness, strength);
                if (refractionEffect != null) {
                    // сначала размытие (inner), поверх него преломление (outer)
                    effect = RenderEffect.createChainEffect(refractionEffect, blurEffect);
                }
            }
            node.setRenderEffect(effect);
        }

        @RequiresApi(33)
        @Nullable
        private RenderEffect createRefraction(float left, float top, float right, float bottom, float radius,
                                             float thickness, float strength) {
            if (refractionFailed) {
                return null;
            }
            try {
                if (refraction == null) {
                    refraction = new RuntimeShader(LiquidGlassController.REFRACTION_AGSL);
                }
                refraction.setFloatUniform("uRect", left, top, right, bottom);
                refraction.setFloatUniform("uRadius", Math.max(0f, radius));
                refraction.setFloatUniform("uThickness", Math.max(1f, thickness));
                refraction.setFloatUniform("uIndex", LiquidGlassController.REFRACTION_INDEX);
                refraction.setFloatUniform("uStrength", Math.max(0f, strength));
                return RenderEffect.createRuntimeShaderEffect(refraction, "content");
            } catch (Throwable e) {
                refractionFailed = true;
                FileLog.e(e);
                return null;
            }
        }

        private void requestCopy(Window window, View decor, Rect area) {
            if (requestInFlight) {
                return;
            }
            final Rect source = new Rect(area);
            final int margin = dp(32);
            source.inset(-margin, -margin);
            if (!source.intersect(0, 0, decor.getWidth(), decor.getHeight())) {
                return;
            }
            final Bitmap destination;
            try {
                destination = Bitmap.createBitmap(
                    Math.max(1, source.width() / DOWNSCALE),
                    Math.max(1, source.height() / DOWNSCALE),
                    Bitmap.Config.ARGB_8888);
            } catch (Throwable e) {
                failedCopies = MAX_FAILED_COPIES;
                FileLog.e(e);
                return;
            }
            requestInFlight = true;
            // копия асинхронная; запрос — вне текущей отрисовки
            host.post(() -> {
                if (released) {
                    requestInFlight = false;
                    return;
                }
                try {
                    PixelCopy.request(window, source, destination, result -> {
                        requestInFlight = false;
                        if (released) {
                            return;
                        }
                        if (result == PixelCopy.SUCCESS) {
                            bitmap = destination;
                            captured.set(source);
                            nodeDirty = true;
                            failedCopies = 0;
                        } else {
                            failedCopies++;
                        }
                        host.invalidate();
                    }, new Handler(Looper.getMainLooper()));
                } catch (Throwable e) {
                    requestInFlight = false;
                    failedCopies = MAX_FAILED_COPIES;
                    FileLog.e(e);
                    host.invalidate();
                }
            });
        }

        void release() {
            released = true;
            bitmap = null;
            node.setRenderEffect(null);
            node.discardDisplayList();
        }
    }
}
