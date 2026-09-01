package io.nekohasekai.sagernet.ui

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutStunBinding
import io.nekohasekai.sagernet.ktx.readableMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libcore.Libcore

class StunActivity : ThemedActivity() {

    private lateinit var binding: LayoutStunBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutStunBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.stun_test)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.baseline_arrow_back_24)
        }
        binding.stunTest.setOnClickListener {
            doTest()
        }
    }

    fun doTest() {
        binding.waitLayout.isVisible = true
        val server = binding.natStunServer.text.toString()
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.Default) {
                    val stunResult = Libcore.stunTest(server)
                    if (stunResult!!.success) {
                        stunResult.text
                    } else {
                        throw Exception(stunResult.text)
                    }
                }
            } catch (e: Exception) {
                binding.waitLayout.isVisible = false
                AlertDialog.Builder(this@StunActivity)
                    .setTitle(R.string.error_title)
                    .setMessage(e.readableMessage)
                    .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                    .setOnCancelListener { finish() }
                    .runCatching { show() }
                return@launch
            }
            binding.waitLayout.isVisible = false
            binding.natResult.text = result
        }
    }

}
