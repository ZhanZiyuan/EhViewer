package com.ehviewer.core.mainthread

import javax.swing.SwingUtilities

internal actual val isMainThread: Boolean get() = SwingUtilities.isEventDispatchThread()
