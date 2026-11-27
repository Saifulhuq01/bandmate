package app.bandmate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.bandmate.ui.BandTheme
import app.bandmate.ui.C
import app.bandmate.ui.T

/** Shown by Health Connect when you ask why BandMate wants step data. */
class PrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BandTheme {
                Column(
                    Modifier.fillMaxSize().background(C.Bg).safeDrawingPadding().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("How BandMate uses your health data", style = T.Title)
                    Text("BandMate only READS your step count from Health Connect, so it can show the steps your phone counted when you weren't wearing your band.", style = T.Body)
                    Text("It never writes to Health Connect, never uploads your data anywhere, and keeps everything on this phone. You can turn this off any time in BandMate (Band → Count phone steps too) or in Health Connect.", style = T.Body)
                }
            }
        }
    }
}
