package ro.cobrabm.fexdroid

// RESTORE_FAILED - apply local fix: rename fun setProfile to loadProfile
object OnScreenControls {
    const val VIRTUAL_SLOT = 3
    var profile by androidx.compose.runtime.mutableStateOf<ControlProfile?>(null); private set
    fun loadProfile(p: ControlProfile?) { profile = p }
    fun reset() {}
    fun filter(e: android.view.MotionEvent): android.view.MotionEvent? = e
    @androidx.compose.runtime.Composable
    fun Overlay(profile: ControlProfile, onMenu: () -> Unit = {}) {}
}
