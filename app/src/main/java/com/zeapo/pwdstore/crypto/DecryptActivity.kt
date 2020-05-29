/*
 * Copyright © 2014-2020 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.zeapo.pwdstore.crypto

import android.content.ClipData
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.github.ajalt.timberkt.e
import com.zeapo.pwdstore.PasswordEntry
import com.zeapo.pwdstore.R
import com.zeapo.pwdstore.databinding.DecryptLayoutBinding
import com.zeapo.pwdstore.utils.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.msfjarvis.openpgpktx.util.OpenPgpApi
import org.openintents.openpgp.IOpenPgpService2
import java.io.ByteArrayOutputStream
import java.io.File

class DecryptActivity : BasePgpActivity(R.layout.decrypt_layout) {
    private val binding by viewBinding(DecryptLayoutBinding::inflate)

    private val relativeParentPath by lazy { getParentPath(fullPath, repoPath) }
    private var passwordEntry: PasswordEntry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        with(binding) {
            passwordCategory.text = relativeParentPath
            passwordFile.text = name
            passwordFile.setOnLongClickListener {
                val clipboard = clipboard ?: return@setOnLongClickListener false
                val clip = ClipData.newPlainText("pgp_handler_result_pm", name)
                clipboard.setPrimaryClip(clip)
                showSnackbar(resources.getString(R.string.clipboard_username_toast_text))
                true
            }
            try {
                passwordLastChanged.text = resources.getString(R.string.last_changed, lastChangedString)
            } catch (e: RuntimeException) {
                passwordLastChanged.visibility = View.GONE
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.pgp_handler, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> onBackPressed()
            R.id.share_password_as_plaintext -> shareAsPlaintext()
            R.id.copy_password -> copyPasswordToClipboard()
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (data == null) {
            setResult(RESULT_CANCELED, null)
            finish()
            return
        }

        // try again after user interaction
        if (resultCode == RESULT_OK) {
            when (requestCode) {
                REQUEST_DECRYPT -> decryptAndVerify(data)
                else -> {
                    setResult(RESULT_OK)
                    finish()
                }
            }
        } else if (resultCode == RESULT_CANCELED) {
            setResult(RESULT_CANCELED, data)
            finish()
        }
    }

    override fun onBound(service: IOpenPgpService2) {
        super.onBound(service)
        decryptAndVerify()
    }

    private fun copyUsernameToClipboard(username: String?) {
        val clipboard = clipboard ?: return
        val clip = ClipData.newPlainText("pgp_handler_result_pm", username)
        clipboard.setPrimaryClip(clip)
        showSnackbar(resources.getString(R.string.clipboard_username_toast_text))
    }

    private fun copyPasswordToClipboard() {
        val clipboard = clipboard ?: return
        val pass = passwordEntry?.password
        val clip = ClipData.newPlainText("pgp_handler_result_pm", pass)
        clipboard.setPrimaryClip(clip)

        var clearAfter = 45
        try {
            clearAfter = Integer.parseInt(settings.getString("general_show_time", "45") as String)
        } catch (e: NumberFormatException) {
            // ignore and keep default
        }

        if (clearAfter != 0) {
            //setTimer()
            showSnackbar(this.resources.getString(R.string.clipboard_password_toast_text, clearAfter))
        } else {
            showSnackbar(this.resources.getString(R.string.clipboard_password_no_clear_toast_text))
        }
    }

    private fun shareAsPlaintext() {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, passwordEntry?.password)
            type = "text/plain"
        }
        // Always show a picker to give the user a chance to cancel
        startActivity(Intent.createChooser(sendIntent, resources.getText(R.string.send_plaintext_password_to)))
    }

    private fun decryptAndVerify(receivedIntent: Intent? = null) {
        val data = receivedIntent ?: Intent()
        data.action = OpenPgpApi.ACTION_DECRYPT_VERIFY

        val inputStream = File(fullPath).inputStream()
        val outputStream = ByteArrayOutputStream()

        lifecycleScope.launch(Dispatchers.IO) {
            api?.executeApiAsync(data, inputStream, outputStream) { result ->
                when (result?.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
                    OpenPgpApi.RESULT_CODE_SUCCESS -> {
                        try {
                            val showPassword = settings.getBoolean("show_password", true)
                            val showExtraContent = settings.getBoolean("show_extra_content", true)
                            val monoTypeface = Typeface.createFromAsset(assets, "fonts/sourcecodepro.ttf")
                            val entry = PasswordEntry(outputStream)

                            passwordEntry = entry

                            with(binding) {
                                cryptoContainerDecrypt.visibility = View.VISIBLE
                                if (entry.password.isEmpty()) {
                                    cryptoPasswordShow.visibility = View.GONE
                                    cryptoPasswordShowLabel.visibility = View.GONE
                                } else {
                                    cryptoPasswordShow.visibility = View.VISIBLE
                                    cryptoPasswordShowLabel.visibility = View.VISIBLE
                                }
                                cryptoPasswordShow.typeface = monoTypeface
                                cryptoPasswordShow.text = entry.password

                                cryptoPasswordToggleShow.visibility = if (showPassword) View.GONE else View.VISIBLE

                                if (entry.hasExtraContent()) {
                                    cryptoExtraShow.typeface = monoTypeface
                                    cryptoExtraShow.text = entry.extraContent

                                    if (showExtraContent) {
                                        cryptoExtraShowLayout.visibility = View.VISIBLE
                                        cryptoExtraToggleShow.visibility = View.GONE
                                        cryptoExtraShow.transformationMethod = null
                                    } else {
                                        cryptoExtraShowLayout.visibility = View.GONE
                                        cryptoExtraToggleShow.visibility = View.VISIBLE
                                        cryptoExtraToggleShow.setOnCheckedChangeListener { _, _ ->
                                            cryptoExtraShow.text = entry.extraContent
                                        }

                                        cryptoExtraShow.transformationMethod = object : PasswordTransformationMethod() {
                                            override fun getTransformation(source: CharSequence, view: View): CharSequence {
                                                return if (cryptoExtraToggleShow.isChecked) source else super.getTransformation(source, view)
                                            }
                                        }
                                    }

                                    if (entry.hasUsername()) {
                                        cryptoUsernameShow.visibility = View.VISIBLE
                                        cryptoUsernameShowLabel.visibility = View.VISIBLE
                                        cryptoCopyUsername.visibility = View.VISIBLE
                                        cryptoCopyUsername.setOnClickListener { copyUsernameToClipboard(entry.username) }
                                        cryptoUsernameShow.typeface = monoTypeface
                                        cryptoUsernameShow.text = entry.username
                                    } else {
                                        cryptoUsernameShow.visibility = View.GONE
                                        cryptoUsernameShowLabel.visibility = View.GONE
                                        cryptoCopyUsername.visibility = View.GONE
                                    }
                                }
                            }

                            if (settings.getBoolean("copy_on_decrypt", true)) {
                                copyPasswordToClipboard()
                            }
                        } catch (e: Exception) {
                            e(e)
                        }
                    }
                    OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED -> handleUserInteractionRequest(result, REQUEST_DECRYPT)
                    OpenPgpApi.RESULT_CODE_ERROR -> handleError(result)
                }
            }
        }
    }
}
