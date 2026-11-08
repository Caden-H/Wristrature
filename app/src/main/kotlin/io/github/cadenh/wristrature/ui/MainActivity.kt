package io.github.cadenh.wristrature.ui

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import kotlinx.coroutines.flow.MutableStateFlow

/** The single activity: hosts [AppRoot], receives the book to open from the tile and complications. */
class MainActivity : ComponentActivity() {
    // The tile and complications pass the book to open: in onCreate on a cold start, in onNewIntent when running.
    private val requestedBook = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A saved state means the activity is being restored; the earlier request must not be replayed.
        if (savedInstanceState == null) requestedBook.value = intent?.getStringExtra(EXTRA_BOOK)
        setContent { AppRoot(requestedBook, onRequestHandled = { requestedBook.value = null }) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_BOOK)?.let { requestedBook.value = it }
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean =
        io.github.cadenh.wristrature.ui.reader.CrownInput.dispatch(ev, ViewConfiguration.get(this), this) ||
            super.dispatchGenericMotionEvent(ev)

    companion object {
        const val EXTRA_BOOK = "bookId"
    }
}
