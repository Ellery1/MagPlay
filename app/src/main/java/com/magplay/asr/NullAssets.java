package com.magplay.asr;

import android.content.res.AssetManager;

/**
 * 传一个「值是 null 但类型不是 null」的 AssetManager。
 *
 * 为什么需要它：sherpa-onnx 的 JNI 在拿到**绝对路径**时会要求 assetManager 为 null
 * （否则报 "You are using an absolute path ..., but assetManager is NOT set to null"
 * 然后直接 exit 掉进程）。Kotlin 里 `null as AssetManager` 会抛 NPE、`null!!` 同样，
 * 而 Java 方法返回 null 时 Kotlin 视为平台类型，可以直接传给非空参数。
 *
 * 对应上游说明：https://github.com/k2-fsa/sherpa-onnx/issues/2562
 */
public final class NullAssets {
    private NullAssets() {}

    public static AssetManager get() {
        return null;
    }
}
