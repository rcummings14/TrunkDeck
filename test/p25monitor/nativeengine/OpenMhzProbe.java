package p25monitor.nativeengine;

/** Test access to the OpenMHz form fields (package-private in the uploader). */
public final class OpenMhzProbe {
    private OpenMhzProbe() {}
    public static String sourceList(OpenMhzUploader.Call c, java.util.function.IntFunction<String> tag) {
        return (tag == null ? OpenMhzUploader.fields(c) : OpenMhzUploader.fields(c, tag)).get("source_list");
    }
}
