package app.opentv.launcher.ui.components;

import android.graphics.RuntimeShader;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.nativeruntime.DefaultNativeRuntimeLoader;
import org.robolectric.nativeruntime.RuntimeShaderNatives;
import org.robolectric.shadows.ShadowNativeRuntimeShader;

/** Robolectric 4.16 uses older SkSL and leaves API 34 scalar-uniform updates unimplemented. */
@Implements(value = RuntimeShader.class, minSdk = 33, callNativeMethodsByDefault = true)
public class HostRuntimeShader extends ShadowNativeRuntimeShader {
    @Implementation
    protected static long nativeCreateBuilder(String source) {
        DefaultNativeRuntimeLoader.injectAndLoad();
        String compatible = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\.eval\\(")
                .matcher(source).replaceAll("sample($1, ");
        return RuntimeShaderNatives.nativeCreateBuilder(compatible);
    }

    @Implementation
    protected static void nativeUpdateUniforms(long builder, String name, float x, float y, float z, float w, int count) {
        RuntimeShaderNatives.nativeUpdateUniforms(builder, name, Arrays.copyOf(new float[] {x, y, z, w}, count));
    }
}
