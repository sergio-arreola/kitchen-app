package dev.kitchenapp.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.kitchenapp.sharedui.KitchenApp

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Kitchen",
    ) {
        KitchenApp()
    }
}
