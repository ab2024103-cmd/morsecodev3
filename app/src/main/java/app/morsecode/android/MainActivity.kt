package app.morsecode.android

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import app.morsecode.android.databinding.ActivityMainBinding

/**
 * The single activity (§8.1). Fragment destinations and the four-tab shell
 * (§5.1, §5.2) are built in Stage 3; ACTION_SEND / ACTION_SEND_MULTIPLE intake
 * (§3.6) is wired in the same stage, once there is a send flow to queue into.
 *
 * Screens receive their purpose as an explicit navigation argument, never
 * inferred from incidental state (§5.4, §20.4).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }
}
