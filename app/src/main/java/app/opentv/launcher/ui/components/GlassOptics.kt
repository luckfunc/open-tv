package app.opentv.launcher.ui.components

import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap

internal interface GlassOptics {
    val brush: Brush
    fun update(frame: SceneFrame.Ready, viewport: SceneViewport, placement: SurfacePlacement, size: Size, radius: Float, density: Float)
}

/** Local refraction, not a full-screen RenderEffect. No clock or idle animation is attached. */
@RequiresApi(33)
internal class RefractiveGlass : GlassOptics {
    private val runtime = RuntimeShader(SOURCE)
    private var base: ImageBitmap? = null
    private var diffused: ImageBitmap? = null
    override val brush: Brush = ShaderBrush(runtime)

    init {
        if (!reported) { reported = true; Log.i("OpenTVGlass", "Local refractive glass shader initialized") }
    }

    override fun update(frame: SceneFrame.Ready, viewport: SceneViewport, placement: SurfacePlacement, size: Size, radius: Float, density: Float) {
        if (base !== frame.base) {
            runtime.setInputShader("scene", BitmapShader(frame.base.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            base = frame.base
        }
        if (diffused !== frame.diffused) {
            runtime.setInputShader("frost", BitmapShader(frame.diffused.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            diffused = frame.diffused
        }
        val origin = placement.origin - viewport.origin
        runtime.setFloatUniform("origin", origin.x, origin.y)
        runtime.setFloatUniform("axisX", placement.axisX.x, placement.axisX.y)
        runtime.setFloatUniform("axisY", placement.axisY.x, placement.axisY.y)
        runtime.setFloatUniform("extent", size.width, size.height)
        runtime.setFloatUniform("sceneScale", frame.base.width.toFloat() / viewport.size.width, frame.base.height.toFloat() / viewport.size.height)
        runtime.setFloatUniform("frostScale", frame.diffused.width.toFloat() / viewport.size.width, frame.diffused.height.toFloat() / viewport.size.height)
        runtime.setFloatUniform("radius", radius.coerceIn(0.001f, (size.minDimension / 2f).coerceAtLeast(0.001f)))
        runtime.setFloatUniform("pixelDensity", density)
        runtime.setFloatUniform("sceneDimming", frame.dimming)
    }

    companion object {
        @Volatile private var reported = false
        // Independent approximation: Apple's rendering implementation and exact constants are private.
        private const val SOURCE = """
            uniform shader scene;
            uniform shader frost;
            uniform float2 origin;
            uniform float2 axisX;
            uniform float2 axisY;
            uniform float2 extent;
            uniform float2 sceneScale;
            uniform float2 frostScale;
            uniform float radius;
            uniform float pixelDensity;
            uniform float sceneDimming;

            half4 main(float2 p) {
                float2 centered = p - extent * 0.5;
                float2 q = abs(centered) - (extent * 0.5 - radius);
                float2 outer = max(q, float2(0.0));
                float distance = length(outer) + min(max(q.x, q.y), 0.0) - radius;
                float2 normal;
                if (length(outer) > 0.001) normal = normalize(outer) * sign(centered);
                else if (q.x > q.y) normal = float2(sign(centered.x), 0.0);
                else normal = float2(0.0, sign(centered.y));

                float inward = max(-distance, 0.0);
                float band = min(radius * 0.42, 11.0 * pixelDensity);
                float edge = 1.0 - smoothstep(0.0, max(band, 1.0), inward);
                float lens = pow(edge, 1.8);
                float2 root = origin + axisX * p.x + axisY * p.y;
                float2 rootNormal = axisX * normal.x + axisY * normal.y;
                float2 refracted = root + rootNormal * lens * 6.0 * pixelDensity;
                half3 soft = frost.eval(refracted * frostScale).rgb;
                half3 sharp = scene.eval(refracted * sceneScale).rgb;
                half3 color = mix(soft, sharp, half(0.12 + 0.48 * lens));
                half luminance = dot(color, half3(0.2126, 0.7152, 0.0722));
                color = mix(half3(luminance), color, half(1.12));
                color = color * half((1.0 - sceneDimming) * 0.88) + half3(0.018, 0.022, 0.028);

                float light = max(dot(normal, normalize(float2(-0.65, -0.76))), 0.0);
                float opposite = max(dot(normal, normalize(float2(0.65, 0.76))), 0.0);
                float rim = 1.0 - smoothstep(0.0, 1.6 * pixelDensity, inward);
                color += half3(pow(light, 2.0) * rim * 0.65 + edge * light * 0.08);
                color -= half3(opposite * rim * 0.10);
                return half4(clamp(color, half3(0.0), half3(1.0)), half(1.0 - smoothstep(-0.6, 0.6, distance)));
            }
        """
    }
}
