package by.denor.scratch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import by.denor.scratch.ui.theme.ScratchMechanicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScratchMechanicTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column (
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        ScratchOverlay(
                            modifier = Modifier.padding(top = 16.dp),
                            config = ScratchOverlayConfig(
                                clipShape = RoundedCornerShape(16.dp)
                            ),
                            revealedContent = {
                                Image(
                                    modifier = Modifier.size(300.dp),
                                    painter = painterResource(id = R.drawable.reveal),
                                    contentDescription = null
                                )
                            },
                            scratchableContent = {
                                Image(
                                    modifier = Modifier.size(300.dp),
                                    painter = painterResource(id = R.drawable.scratch),
                                    contentDescription = null
                                )
                            },
                        )
                        Spacer(Modifier.height(40.dp))

                        ScratchOverlay(
                            config = ScratchOverlayConfig(
                                clipShape = CircleShape,
                            ),
                            revealedContent = {
                                Image(
                                    modifier = Modifier.size(300.dp),
                                    painter = painterResource(id = R.drawable.reveal),
                                    contentDescription = null
                                )
                            },
                            scratchableContent = {
                                Image(
                                    modifier = Modifier.size(300.dp),
                                    painter = painterResource(id = R.drawable.scratch),
                                    contentDescription = null
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
