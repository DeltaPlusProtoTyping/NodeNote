package com.nodenote

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * iOS entry point. An iOS app embeds the `shared` framework and shows this
 * view controller (e.g. from SwiftUI via UIViewControllerRepresentable).
 * Requires a macOS host with Xcode to build.
 */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
