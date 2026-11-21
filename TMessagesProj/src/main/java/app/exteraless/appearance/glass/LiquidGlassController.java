package app.exteraless.appearance.glass;

import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.Theme;

import app.exteraless.appearance.AppearanceConfig;

/**
 * Liquid Glass onzgram — единая точка эффекта «жидкого стекла».
 *
 * <p>Уровни по версии Android:
 * <ul>
 *     <li>{@link #TIER_SHADER} (API 33+): AGSL {@link RuntimeShader} — преломление у краёв
 *     (линза по SDF скруглённого прямоугольника), блик и тонировка в цвет темы. Размытие
 *     заднего плана — {@link android.graphics.RenderEffect#createBlurEffect}: у попапов его
 *     делает {@link LiquidGlassPopupBackground}, у шапки / поля ввода / плавающих панелей —
 *     стеклянный движок Telegram (blur3), поверх которого рисуется этот слой.</li>
 *     <li>{@link #TIER_BLUR} (API 31–32): то же размытие через RenderEffect, слой стекла —
 *     градиентная тонировка и полупрозрачная градиентная обводка 1dp.</li>
 *     <li>{@link #TIER_FALLBACK} (ниже 31): полупрозрачный фон и тонкая сплошная обводка,
 *     без шейдеров и без размытия — ничего, что просаживает FPS.</li>
 * </ul>
 *
 * <p>Настройки: {@link AppearanceConfig#liquidGlass} (вкл/выкл) и
 * {@link AppearanceConfig#liquidGlassIntensity} (0..100), экран «Оформление → Размытие».
 * Цвета берутся из {@link Theme} на каждой отрисовке, поэтому светлая / тёмная тема и
 * динамические цвета (Monet) подхватываются без пересоздания.
 *
 * <p>Все методы — только из UI-потока.
 */
public final class LiquidGlassController {

    public static final int TIER_FALLBACK = 0;
    public static final int TIER_BLUR = 1;
    public static final int TIER_SHADER = 2;

    /** Скругление стеклянных попапов в стиле Material 3. */
    public static final float POPUP_RADIUS_DP = 14f;

    private static int tier = -1;

    private LiquidGlassController() {
    }

    /* ---------------- Settings ---------------- */

    public static boolean isEnabled() {
        return AppearanceConfig.liquidGlass();
    }

    /** Интенсивность 0..1. */
    public static float getIntensity() {
        return AppearanceConfig.liquidGlassIntensity() / (float) AppearanceConfig.LIQUID_GLASS_INTENSITY_MAX;
    }

    /** Радиус размытия заднего плана, px: от 6dp до 28dp по интенсивности. */
    public static float getBlurRadius() {
        return dpf2(6f + 22f * getIntensity());
    }

    /** Насколько сильно край стекла «тянет» картинку (преломление), px. */
    public static float getRefractionStrength() {
        return dpf2(4f + 8f * getIntensity());
    }

    public static int getTier() {
        if (tier < 0) {
            if (Build.VERSION.SDK_INT >= 33 && ShaderTier.isAvailable()) {
                tier = TIER_SHADER;
            } else if (Build.VERSION.SDK_INT >= 31) {
                tier = TIER_BLUR;
            } else {
                tier = TIER_FALLBACK;
            }
        }
        return tier;
    }

    /** Шейдер не собрался или упал на устройстве — дальше без него. */
    static void onShaderFailed() {
        tier = Build.VERSION.SDK_INT >= 31 ? TIER_BLUR : TIER_FALLBACK;
    }

    public static boolean isDark(@Nullable Theme.ResourcesProvider resourcesProvider) {
        final int background = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider);
        return ColorUtils.calculateLuminance(background | 0xFF000000) < 0.5;
    }

    /* ---------------- Overlay over a glass shape ---------------- */

    private static final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Matrix shaderMatrix = new Matrix();

    private static LinearGradient fillGradient;
    private static int fillGradientKey;
    private static LinearGradient strokeGradient;
    private static int strokeGradientKey;

    static {
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
    }

    /**
     * Слой стекла поверх уже нарисованной стеклянной формы: преломление краёв, блик, тонировка
     * и обводка 1dp. {@code shape} и {@code bounds} — в координатах {@code canvas}.
     *
     * @param radius радиус скругления формы, px (для расчёта линзы у края)
     * @param alpha  общая прозрачность 0..1 (анимации появления)
     */
    public static void drawOverlay(@NonNull Canvas canvas, @NonNull Path shape, @NonNull RectF bounds,
                                   float radius, float alpha, @Nullable Theme.ResourcesProvider resourcesProvider) {
        if (!isEnabled() || alpha <= 0f || bounds.width() < 1f || bounds.height() < 1f) {
            return;
        }
        final float intensity = getIntensity();
        final boolean dark = isDark(resourcesProvider);
        final int tint = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider);
        final int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, resourcesProvider);
        final int currentTier = getTier();

        boolean drawn = false;
        if (currentTier == TIER_SHADER && Build.VERSION.SDK_INT >= 33) {
            drawn = ShaderTier.draw(canvas, shape, bounds, radius, intensity, dark, tint, accent, alpha);
        }
        if (!drawn) {
            drawGradientFill(canvas, shape, bounds, intensity, dark, tint, accent, alpha, currentTier >= TIER_BLUR);
        }
        drawStroke(canvas, shape, bounds, intensity, dark, alpha, currentTier >= TIER_BLUR);
    }

    private static void drawGradientFill(Canvas canvas, Path shape, RectF bounds, float intensity, boolean dark,
                                         int tint, int accent, float alpha, boolean gradient) {
        if (!gradient) {
            fillPaint.setShader(null);
            fillPaint.setColor(Theme.multAlpha(tint | 0xFF000000, (dark ? 0.12f : 0.18f) * intensity * alpha));
            canvas.drawPath(shape, fillPaint);
            return;
        }
        final int key = (int) (intensity * 100) * 31 + (dark ? 1 : 0) + tint * 7 + accent * 13;
        if (fillGradient == null || fillGradientKey != key) {
            fillGradientKey = key;
            final int top = ColorUtils.setAlphaComponent(Color.WHITE, (int) (255 * (dark ? 0.10f : 0.22f) * intensity));
            final int middle = ColorUtils.setAlphaComponent(tint, (int) (255 * (dark ? 0.08f : 0.10f) * intensity));
            final int bottom = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(tint, accent, 0.25f), (int) (255 * 0.12f * intensity));
            // в единичном пространстве: под форму подгоняется матрицей
            fillGradient = new LinearGradient(0, 0, 0, 1, new int[]{top, middle, bottom}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        }
        shaderMatrix.setScale(1f, bounds.height());
        shaderMatrix.postTranslate(bounds.left, bounds.top);
        fillGradient.setLocalMatrix(shaderMatrix);
        fillPaint.setShader(fillGradient);
        fillPaint.setColor(Color.BLACK);
        fillPaint.setAlpha((int) (255 * alpha));
        canvas.drawPath(shape, fillPaint);
        fillPaint.setShader(null);
    }

    private static void drawStroke(Canvas canvas, Path shape, RectF bounds, float intensity, boolean dark,
                                   float alpha, boolean gradient) {
        // «Скрытый» контур в настройках стекла — обводку не рисуем и здесь
        if (AppearanceConfig.glassOutlineStyle() == 2) {
            return;
        }
        strokePaint.setStrokeWidth(Math.max(1f, dpf2(1f)));
        final float k = 0.35f + 0.65f * intensity;
        if (!gradient) {
            strokePaint.setShader(null);
            strokePaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, (int) (255 * (dark ? 0.12f : 0.30f) * k * alpha)));
            canvas.drawPath(shape, strokePaint);
            return;
        }
        final int key = (int) (k * 100) * 2 + (dark ? 1 : 0);
        if (strokeGradient == null || strokeGradientKey != key) {
            strokeGradientKey = key;
            final int top = ColorUtils.setAlphaComponent(Color.WHITE, (int) (255 * (dark ? 0.38f : 0.75f) * k));
            final int middle = ColorUtils.setAlphaComponent(Color.WHITE, (int) (255 * (dark ? 0.04f : 0.10f) * k));
            final int bottom = ColorUtils.setAlphaComponent(Color.WHITE, (int) (255 * (dark ? 0.16f : 0.35f) * k));
            strokeGradient = new LinearGradient(0, 0, 0, 1, new int[]{top, middle, bottom}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        }
        shaderMatrix.setScale(1f, bounds.height());
        shaderMatrix.postTranslate(bounds.left, bounds.top);
        strokeGradient.setLocalMatrix(shaderMatrix);
        strokePaint.setShader(strokeGradient);
        strokePaint.setColor(Color.BLACK);
        strokePaint.setAlpha((int) (255 * alpha));
        canvas.drawPath(shape, strokePaint);
        strokePaint.setShader(null);
    }

    /* ---------------- Full-width bars ---------------- */

    private static final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint edgePaint = new Paint();
    private static LinearGradient barGradient;
    private static int barGradientKey;
    private static LinearGradient edgeGradient;
    private static int edgeGradientKey;

    /**
     * Стекло на полноширинной панели (шапка списка чатов, классическая панель ввода):
     * мягкий блик сверху вниз и тонкая светящаяся кромка 1dp со стороны контента.
     *
     * @param edgeAtTop кромка сверху (панель ввода) или снизу (шапка)
     */
    public static void drawBarOverlay(@NonNull Canvas canvas, float left, float top, float right, float bottom,
                                      boolean edgeAtTop, @Nullable Theme.ResourcesProvider resourcesProvider) {
        if (!isEnabled() || right - left < 1f || bottom - top < 1f) {
            return;
        }
        final float intensity = getIntensity();
        final boolean dark = isDark(resourcesProvider);
        final boolean gradient = getTier() >= TIER_BLUR;
        final float height = bottom - top;
        final float width = right - left;

        if (gradient) {
            final int key = (int) (intensity * 100) * 4 + (dark ? 1 : 0) + (edgeAtTop ? 2 : 0);
            if (barGradient == null || barGradientKey != key) {
                barGradientKey = key;
                final int strong = ColorUtils.setAlphaComponent(Color.WHITE, (int) (255 * (dark ? 0.05f : 0.12f) * intensity));
                final int none = ColorUtils.setAlphaComponent(Color.WHITE, 0);
                barGradient = edgeAtTop
                    ? new LinearGradient(0, 0, 0, 1, strong, none, Shader.TileMode.CLAMP)
                    : new LinearGradient(0, 0, 0, 1, none, strong, Shader.TileMode.CLAMP);
            }
            shaderMatrix.setScale(1f, height);
            shaderMatrix.postTranslate(left, top);
            barGradient.setLocalMatrix(shaderMatrix);
            barPaint.setShader(barGradient);
            barPaint.setColor(Color.BLACK);
            canvas.drawRect(left, top, right, bottom, barPaint);
            barPaint.setShader(null);
        }

        final float edge = Math.max(1f, dpf2(1f));
        final float edgeY = edgeAtTop ? top : bottom - edge;
        final float k = 0.35f + 0.65f * intensity;
        if (gradient) {
            final int key = (int) (k * 100) * 2 + (dark ? 1 : 0);
            if (edgeGradient == null || edgeGradientKey != key) {
                edgeGradientKey = key;
                final int none = ColorUtils.setAlphaComponent(Color.WHITE, 0);
                final int bright = ColorUtils.setAlphaComponent(Color.WHITE, (int) (255 * (dark ? 0.22f : 0.55f) * k));
                edgeGradient = new LinearGradient(0, 0, 1, 0, new int[]{none, bright, bright, none}, new float[]{0f, 0.2f, 0.8f, 1f}, Shader.TileMode.CLAMP);
            }
            shaderMatrix.setScale(width, 1f);
            shaderMatrix.postTranslate(left, 0);
            edgeGradient.setLocalMatrix(shaderMatrix);
            edgePaint.setShader(edgeGradient);
            edgePaint.setColor(Color.BLACK);
        } else {
            edgePaint.setShader(null);
            edgePaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, (int) (255 * (dark ? 0.10f : 0.25f) * k)));
        }
        canvas.drawRect(left, edgeY, right, edgeY + edge, edgePaint);
        edgePaint.setShader(null);
    }

    /* ---------------- Android 13+: AGSL ---------------- */

    /**
     * Слой стекла без выборки фона: линза у края по SDF скруглённого прямоугольника (светлая
     * кромка со стороны света, тёмная — с противоположной), эллиптический блик и тонировка.
     * Результат — премультиплицированный цвет.
     */
    static final String OVERLAY_AGSL =
        "uniform float2 uOrigin;\n" +
        "uniform float2 uSize;\n" +
        "uniform float uRadius;\n" +
        "uniform float uIntensity;\n" +
        "uniform float uDark;\n" +
        "uniform float4 uTint;\n" +
        "uniform float4 uAccent;\n" +
        "\n" +
        "float sdRoundRect(float2 p, float2 b, float r) {\n" +
        "    float2 q = abs(p) - b + float2(r);\n" +
        "    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;\n" +
        "}\n" +
        "\n" +
        "half4 main(float2 coord) {\n" +
        "    float2 hs = uSize * 0.5;\n" +
        "    float2 p = coord - uOrigin - hs;\n" +
        "    float r = min(uRadius, min(hs.x, hs.y));\n" +
        "    float d = sdRoundRect(p, hs, r);\n" +
        "    float band = max(r * 0.75, 6.0);\n" +
        "    float edge = 1.0 - smoothstep(0.0, band, -d);\n" +
        "    float2 ex = float2(0.75, 0.0);\n" +
        "    float2 ey = float2(0.0, 0.75);\n" +
        "    float2 n = float2(sdRoundRect(p + ex, hs, r) - sdRoundRect(p - ex, hs, r),\n" +
        "                      sdRoundRect(p + ey, hs, r) - sdRoundRect(p - ey, hs, r));\n" +
        "    n = n / max(length(n), 0.0001);\n" +
        "    float2 light = normalize(float2(-0.45, -0.89));\n" +
        "    float facing = dot(n, light);\n" +
        "    float rimLit = edge * edge * max(facing, 0.0);\n" +
        "    float rimShade = edge * edge * edge * max(-facing, 0.0);\n" +
        "    float2 uv = (coord - uOrigin) / max(uSize, float2(1.0));\n" +
        "    float2 g = (uv - float2(0.3, 0.05)) * float2(1.7, 3.4);\n" +
        "    float glare = exp(-dot(g, g) * 2.5);\n" +
        "    float3 tint = mix(uTint.rgb, uAccent.rgb, 0.12 + 0.10 * uv.y);\n" +
        "    float k = uIntensity;\n" +
        "    float tintA = mix(0.07, 0.14, uDark) * k;\n" +
        "    float hiA = clamp((0.50 * rimLit + 0.22 * glare) * k * mix(1.0, 0.55, uDark), 0.0, 1.0);\n" +
        "    float shA = clamp(0.16 * rimShade * k, 0.0, 1.0);\n" +
        "    float3 col = tint * tintA;\n" +
        "    float a = tintA;\n" +
        "    col = float3(hiA) + col * (1.0 - hiA);\n" +
        "    a = hiA + a * (1.0 - hiA);\n" +
        "    col = col * (1.0 - shA);\n" +
        "    a = shA + a * (1.0 - shA);\n" +
        "    return half4(half3(col), half(a));\n" +
        "}\n";

    /**
     * Преломление для {@link android.graphics.RenderEffect#createRuntimeShaderEffect}: у края
     * формы выборка смещается внутрь (линза), с лёгким хроматическим разнесением каналов.
     * Работает поверх уже размытого заднего плана (RenderEffect.createChainEffect).
     */
    static final String REFRACTION_AGSL =
        "uniform shader content;\n" +
        "uniform float4 uRect;\n" +
        "uniform float uRadius;\n" +
        "uniform float uStrength;\n" +
        "\n" +
        "float sdRoundRect(float2 p, float2 b, float r) {\n" +
        "    float2 q = abs(p) - b + float2(r);\n" +
        "    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;\n" +
        "}\n" +
        "\n" +
        "half4 main(float2 coord) {\n" +
        "    float2 hs = max((uRect.zw - uRect.xy) * 0.5, float2(0.5));\n" +
        "    float2 p = coord - (uRect.xy + hs);\n" +
        "    float r = min(uRadius, min(hs.x, hs.y));\n" +
        "    float d = sdRoundRect(p, hs, r);\n" +
        "    float band = max(r, 3.0);\n" +
        "    float t = clamp(1.0 + d / band, 0.0, 1.0);\n" +
        "    float2 ex = float2(0.5, 0.0);\n" +
        "    float2 ey = float2(0.0, 0.5);\n" +
        "    float2 n = float2(sdRoundRect(p + ex, hs, r) - sdRoundRect(p - ex, hs, r),\n" +
        "                      sdRoundRect(p + ey, hs, r) - sdRoundRect(p - ey, hs, r));\n" +
        "    n = n / max(length(n), 0.0001);\n" +
        "    float2 offset = -n * uStrength * t * t;\n" +
        "    half4 base = content.eval(coord + offset);\n" +
        "    half4 red = content.eval(coord + offset * 1.2);\n" +
        "    half4 blue = content.eval(coord + offset * 0.8);\n" +
        "    return half4(red.r, base.g, blue.b, base.a);\n" +
        "}\n";

    @RequiresApi(33)
    static final class ShaderTier {

        private static RuntimeShader shader;
        private static boolean failed;
        private static final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private ShaderTier() {
        }

        static boolean isAvailable() {
            return obtain() != null;
        }

        @Nullable
        private static RuntimeShader obtain() {
            if (failed) {
                return null;
            }
            if (shader == null) {
                try {
                    shader = new RuntimeShader(OVERLAY_AGSL);
                } catch (Throwable e) {
                    failed = true;
                    FileLog.e(e);
                    return null;
                }
            }
            return shader;
        }

        static boolean draw(Canvas canvas, Path shape, RectF bounds, float radius, float intensity, boolean dark,
                            int tint, int accent, float alpha) {
            final RuntimeShader s = obtain();
            if (s == null) {
                return false;
            }
            try {
                s.setFloatUniform("uOrigin", bounds.left, bounds.top);
                s.setFloatUniform("uSize", bounds.width(), bounds.height());
                s.setFloatUniform("uRadius", Math.max(0f, radius));
                s.setFloatUniform("uIntensity", intensity);
                s.setFloatUniform("uDark", dark ? 1f : 0f);
                s.setFloatUniform("uTint", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f, 1f);
                s.setFloatUniform("uAccent", Color.red(accent) / 255f, Color.green(accent) / 255f, Color.blue(accent) / 255f, 1f);
                paint.setShader(s);
                paint.setAlpha((int) (255 * Math.min(1f, alpha)));
                canvas.drawPath(shape, paint);
                return true;
            } catch (Throwable e) {
                failed = true;
                FileLog.e(e);
                onShaderFailed();
                return false;
            }
        }
    }
}
