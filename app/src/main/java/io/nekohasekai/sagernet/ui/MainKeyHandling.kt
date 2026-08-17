package io.nekohasekai.sagernet.ui

import android.view.KeyEvent
import io.nekohasekai.sagernet.R

internal fun MainActivity.handleKeyDown(
    keyCode: Int,
    event: KeyEvent,
    superHandler: () -> Boolean,
): Boolean {
    when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> {
            if (superHandler()) return true
            binding.drawerLayout.open()
            navigation.requestFocus()
        }

        KeyEvent.KEYCODE_DPAD_RIGHT -> {
            if (binding.drawerLayout.isOpen) {
                binding.drawerLayout.close()
                return true
            }
        }
    }

    if (superHandler()) return true
    if (binding.drawerLayout.isOpen) return false
    val fragment = supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
    return fragment != null && fragment.onKeyDown(keyCode, event)
}
