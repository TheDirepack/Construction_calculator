# Keep ViewModel subclasses instantiated reflectively by androidx.lifecycle.viewmodel.compose.viewModel()
-keep public class * extends androidx.lifecycle.ViewModel {
    public <init>(...);
}
-keep public class * extends com.thedirepack.constructioncalc.core.ui.tool.ToolInputsViewModel {
    public <init>(...);
}

# Keep PDFBox-Android classes and resources accessed reflectively
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**

# Keep OpenCV JNI bindings
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**
