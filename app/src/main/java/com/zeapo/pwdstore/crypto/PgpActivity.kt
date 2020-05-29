/*
 * Copyright © 2014-2020 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.zeapo.pwdstore.crypto

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.method.PasswordTransformationMethod
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.github.ajalt.timberkt.Timber.e
import com.github.ajalt.timberkt.Timber.i
import com.github.ajalt.timberkt.Timber.tag
import com.google.android.material.snackbar.Snackbar
import com.zeapo.pwdstore.ClipboardService
import com.zeapo.pwdstore.PasswordEntry
import com.zeapo.pwdstore.R
import com.zeapo.pwdstore.UserPreference
import com.zeapo.pwdstore.autofill.oreo.AutofillPreferences
import com.zeapo.pwdstore.autofill.oreo.DirectoryStructure
import com.zeapo.pwdstore.ui.dialogs.PasswordGeneratorDialogFragment
import com.zeapo.pwdstore.ui.dialogs.XkPasswordGeneratorDialogFragment
import kotlinx.android.synthetic.main.password_creation_activity.crypto_extra_edit
import kotlinx.android.synthetic.main.password_creation_activity.crypto_password_category
import kotlinx.android.synthetic.main.password_creation_activity.crypto_password_edit
import kotlinx.android.synthetic.main.password_creation_activity.encrypt_username
import kotlinx.android.synthetic.main.password_creation_activity.generate_password
import kotlinx.android.synthetic.main.password_creation_activity.password_file_edit
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import me.msfjarvis.openpgpktx.util.OpenPgpApi
import me.msfjarvis.openpgpktx.util.OpenPgpApi.Companion.RESULT_CODE
import me.msfjarvis.openpgpktx.util.OpenPgpApi.Companion.RESULT_CODE_ERROR
import me.msfjarvis.openpgpktx.util.OpenPgpApi.Companion.RESULT_CODE_SUCCESS
import me.msfjarvis.openpgpktx.util.OpenPgpApi.Companion.RESULT_CODE_USER_INTERACTION_REQUIRED
import me.msfjarvis.openpgpktx.util.OpenPgpApi.Companion.RESULT_ERROR
import me.msfjarvis.openpgpktx.util.OpenPgpApi.Companion.RESULT_INTENT
import me.msfjarvis.openpgpktx.util.OpenPgpServiceConnection
import org.apache.commons.io.FilenameUtils
import org.openintents.openpgp.IOpenPgpService2
import org.openintents.openpgp.OpenPgpError

class PgpActivity : AppCompatActivity(), OpenPgpServiceConnection.OnBound {
    private val clipboard by lazy { getSystemService<ClipboardManager>() }
    private var passwordEntry: PasswordEntry? = null
    private var api: OpenPgpApi? = null

    private var editPass: String? = null

    private val suggestedName by lazy { intent.getStringExtra("SUGGESTED_NAME") }
    private val suggestedPass by lazy { intent.getStringExtra("SUGGESTED_PASS") }
    private val suggestedExtra by lazy { intent.getStringExtra("SUGGESTED_EXTRA") }
    private val shouldGeneratePassword by lazy { intent.getBooleanExtra("GENERATE_PASSWORD", false) }

    private val operation: String by lazy { intent.getStringExtra("OPERATION") }
    private val repoPath: String by lazy { intent.getStringExtra("REPO_PATH") }

    private val fullPath: String by lazy { intent.getStringExtra("FILE_PATH") }
    private val name: String by lazy { getName(fullPath) }
    private val lastChangedString: CharSequence by lazy {
        getLastChangedString(
            intent.getLongExtra(
                "LAST_CHANGED_TIMESTAMP",
                -1L
            )
        )
    }
    private val relativeParentPath: String by lazy { getParentPath(fullPath, repoPath) }

    val settings: SharedPreferences by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private var mServiceConnection: OpenPgpServiceConnection? = null
    private var delayTask: DelayShow? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        tag(TAG)

        // some persistence
        val providerPackageName = settings.getString("openpgp_provider_list", "")

        if (TextUtils.isEmpty(providerPackageName)) {
            showSnackbar(resources.getString(R.string.provider_toast_text), Snackbar.LENGTH_LONG)
            val intent = Intent(this, UserPreference::class.java)
            startActivityForResult(intent, OPEN_PGP_BOUND)
        } else {
            // bind to service
            mServiceConnection = OpenPgpServiceConnection(this, providerPackageName, this)
            mServiceConnection?.bindToService()
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
        }

        when (operation) {
            "ENCRYPT" -> {
                setContentView(R.layout.password_creation_activity)

                generate_password?.setOnClickListener {
                    generatePassword()
                }

                title = getString(R.string.new_password_title)
                crypto_password_category.apply {
                    // If the activity has been provided with suggested info or is meant to generate
                    // a password, we allow the user to edit the path, otherwise we style the
                    // EditText like a TextView.
                    if (suggestedName != null || suggestedPass != null || shouldGeneratePassword) {
                        isEnabled = true
                    } else {
                        setBackgroundColor(getColor(android.R.color.transparent))
                    }
                    val path = getRelativePath(fullPath, repoPath)
                    // Keep empty path field visible if it is editable.
                    if (path.isEmpty() && !isEnabled)
                        visibility = View.GONE
                    else
                        setText(path)
                }
                suggestedName?.let { password_file_edit.setText(it) }
                // Allow the user to quickly switch between storing the username as the filename or
                // in the encrypted extras. This only makes sense if the directory structure is
                // FileBased.
                if (suggestedName != null &&
                    AutofillPreferences.directoryStructure(this) == DirectoryStructure.FileBased
                ) {
                    encrypt_username.apply {
                        visibility = View.VISIBLE
                        setOnClickListener {
                            if (isChecked) {
                                // User wants to enable username encryption, so we add it to the
                                // encrypted extras as the first line.
                                val username = password_file_edit.text!!.toString()
                                val extras = "username:$username\n${crypto_extra_edit.text!!}"

                                password_file_edit.setText("")
                                crypto_extra_edit.setText(extras)
                            } else {
                                // User wants to disable username encryption, so we extract the
                                // username from the encrypted extras and use it as the filename.
                                val entry = PasswordEntry("PASSWORD\n${crypto_extra_edit.text!!}")
                                val username = entry.username

                                // username should not be null here by the logic in
                                // updateEncryptUsernameState, but it could still happen due to
                                // input lag.
                                if (username != null) {
                                    password_file_edit.setText(username)
                                    crypto_extra_edit.setText(entry.extraContentWithoutUsername)
                                }
                            }
                            updateEncryptUsernameState()
                        }
                    }
                    password_file_edit.doOnTextChanged { _, _, _, _ -> updateEncryptUsernameState() }
                    crypto_extra_edit.doOnTextChanged { _, _, _, _ -> updateEncryptUsernameState() }
                    updateEncryptUsernameState()
                }
                suggestedPass?.let {
                    crypto_password_edit.setText(it)
                    crypto_password_edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                suggestedExtra?.let { crypto_extra_edit.setText(it) }
                if (shouldGeneratePassword) {
                    generatePassword()
                    crypto_password_edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }
        }
    }

    private fun updateEncryptUsernameState() {
        encrypt_username.apply {
            if (visibility != View.VISIBLE)
                return
            val hasUsernameInFileName = password_file_edit.text!!.toString().isNotBlank()
            // Use PasswordEntry to parse extras for username
            val entry = PasswordEntry("PLACEHOLDER\n${crypto_extra_edit.text!!}")
            val hasUsernameInExtras = entry.hasUsername()
            isEnabled = hasUsernameInFileName xor hasUsernameInExtras
            isChecked = hasUsernameInExtras
        }
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, IntentFilter(ACTION_CLEAR))
    }

    private fun generatePassword() {
        when (settings.getString("pref_key_pwgen_type", KEY_PWGEN_TYPE_CLASSIC)) {
            KEY_PWGEN_TYPE_CLASSIC -> PasswordGeneratorDialogFragment()
                .show(supportFragmentManager, "generator")
            KEY_PWGEN_TYPE_XKPASSWD -> XkPasswordGeneratorDialogFragment()
                .show(supportFragmentManager, "xkpwgenerator")
        }
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        mServiceConnection?.unbindFromService()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        // Do not use the value `operation` in this case as it is not valid when editing
        val menuId = when (intent.getStringExtra("OPERATION")) {
            "ENCRYPT", "EDIT" -> R.menu.pgp_handler_new_password
            else -> R.menu.pgp_handler
        }

        menuInflater.inflate(menuId, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.crypto_cancel_add, android.R.id.home -> {
                finish()
            }
            R.id.copy_password -> copyPasswordToClipBoard()
            R.id.share_password_as_plaintext -> shareAsPlaintext()
            R.id.edit_password -> editPassword()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    /**
     * Shows a simple toast message
     */
    private fun showSnackbar(message: String, length: Int = Snackbar.LENGTH_SHORT) {
        runOnUiThread { Snackbar.make(findViewById(android.R.id.content), message, length).show() }
    }

    /**
     * Handle the case where OpenKeychain returns that it needs to interact with the user
     *
     * @param result The intent returned by OpenKeychain
     * @param requestCode The code we'd like to use to identify the behaviour
     */
    private fun handleUserInteractionRequest(result: Intent, requestCode: Int) {
        i { "RESULT_CODE_USER_INTERACTION_REQUIRED" }

        val pi: PendingIntent? = result.getParcelableExtra(RESULT_INTENT)
        try {
            this@PgpActivity.startIntentSenderFromChild(
                this@PgpActivity, pi?.intentSender, requestCode,
                null, 0, 0, 0
            )
        } catch (e: IntentSender.SendIntentException) {
            e(e) { "SendIntentException" }
        }
    }

    /**
     * Handle the error returned by OpenKeychain
     *
     * @param result The intent returned by OpenKeychain
     */
    private fun handleError(result: Intent) {
        // TODO show what kind of error it is
        /* For example:
         * No suitable key found -> no key in OpenKeyChain
         *
         * Check in open-pgp-lib how their definitions and error code
         */
        val error: OpenPgpError? = result.getParcelableExtra(RESULT_ERROR)
        if (error != null) {
            showSnackbar("Error from OpenKeyChain : " + error.message)
            e { "onError getErrorId: ${error.errorId}" }
            e { "onError getMessage: ${error.message}" }
        }
    }

    private fun initOpenPgpApi() {
        api = api ?: OpenPgpApi(this, mServiceConnection!!.service!!)
    }

    /**
     * Opens EncryptActivity with the information for this file to be edited
     */
    private fun editPassword() {
        setContentView(R.layout.password_creation_activity)
        generate_password?.setOnClickListener {
            when (settings.getString("pref_key_pwgen_type", KEY_PWGEN_TYPE_CLASSIC)) {
                KEY_PWGEN_TYPE_CLASSIC -> PasswordGeneratorDialogFragment()
                    .show(supportFragmentManager, "generator")
                KEY_PWGEN_TYPE_XKPASSWD -> XkPasswordGeneratorDialogFragment()
                    .show(supportFragmentManager, "xkpwgenerator")
            }
        }

        title = getString(R.string.edit_password_title)

        val monoTypeface = Typeface.createFromAsset(assets, "fonts/sourcecodepro.ttf")
        crypto_password_edit.setText(passwordEntry?.password)
        crypto_password_edit.typeface = monoTypeface
        crypto_extra_edit.setText(passwordEntry?.extraContent)
        crypto_extra_edit.typeface = monoTypeface

        crypto_password_category.setText(relativeParentPath)
        password_file_edit.setText(name)
        password_file_edit.isEnabled = false

        delayTask?.cancelAndSignal(true)

        val data = Intent(this, PgpActivity::class.java)
        data.putExtra("OPERATION", "EDIT")
        data.putExtra(PasswordCreationActivity.EXTRA_FROM_DECRYPT, true)
        intent = data
        invalidateOptionsMenu()
    }

    /**
     * Get the Key ids from OpenKeychain
     */
    private fun getKeyIds(receivedIntent: Intent? = null) {
        val data = receivedIntent ?: Intent()
        data.action = OpenPgpApi.ACTION_GET_KEY_IDS
        lifecycleScope.launch(IO) {
            api?.executeApiAsync(data, null, null) { result ->
                when (result?.getIntExtra(RESULT_CODE, RESULT_CODE_ERROR)) {
                    RESULT_CODE_SUCCESS -> {
                        try {
                            val ids = result.getLongArrayExtra(OpenPgpApi.RESULT_KEY_IDS)
                                ?: LongArray(0)
                            val keys = ids.map { it.toString() }.toSet()

                            // use Long
                            settings.edit { putStringSet("openpgp_key_ids_set", keys) }

                            showSnackbar("PGP keys selected")

                            setResult(RESULT_OK)
                            finish()
                        } catch (e: Exception) {
                            e(e) { "An Exception occurred" }
                        }
                    }
                    RESULT_CODE_USER_INTERACTION_REQUIRED -> handleUserInteractionRequest(result, REQUEST_KEY_ID)
                    RESULT_CODE_ERROR -> handleError(result)
                }
            }
        }
    }

    override fun onError(e: Exception) {}

    /**
     * The action to take when the PGP service is bound
     */
    override fun onBound(service: IOpenPgpService2) {
        initOpenPgpApi()
        when (operation) {
            "GET_KEY_ID" -> getKeyIds()
        }
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
                REQUEST_KEY_ID -> getKeyIds(data)
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

    @SuppressLint("ClickableViewAccessibility")
    private inner class HoldToShowPasswordTransformation constructor(button: Button, private val onToggle: Runnable) :
        PasswordTransformationMethod(), View.OnTouchListener {

        private var shown = false

        init {
            button.setOnTouchListener(this)
        }

        override fun getTransformation(charSequence: CharSequence, view: View): CharSequence {
            return if (shown) charSequence else super.getTransformation("12345", view)
        }

        override fun onTouch(view: View, motionEvent: MotionEvent): Boolean {
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    shown = true
                    onToggle.run()
                }
                MotionEvent.ACTION_UP -> {
                    shown = false
                    onToggle.run()
                }
            }
            return false
        }
    }

    private fun copyPasswordToClipBoard() {
        val clipboard = clipboard ?: return
        var pass = passwordEntry?.password

        if (findViewById<TextView>(R.id.crypto_password_show) == null) {
            if (editPass == null) {
                return
            } else {
                pass = editPass
            }
        }

        val clip = ClipData.newPlainText("pgp_handler_result_pm", pass)
        clipboard.setPrimaryClip(clip)

        var clearAfter = 45
        try {
            clearAfter = Integer.parseInt(settings.getString("general_show_time", "45") as String)
        } catch (e: NumberFormatException) {
            // ignore and keep default
        }

        if (clearAfter != 0) {
            setTimer()
            showSnackbar(this.resources.getString(R.string.clipboard_password_toast_text, clearAfter))
        } else {
            showSnackbar(this.resources.getString(R.string.clipboard_password_no_clear_toast_text))
        }
    }

    private fun copyUsernameToClipBoard(username: String) {
        val clipboard = clipboard ?: return
        val clip = ClipData.newPlainText("pgp_handler_result_pm", username)
        clipboard.setPrimaryClip(clip)
        showSnackbar(resources.getString(R.string.clipboard_username_toast_text))
    }

    private fun shareAsPlaintext() {
        if (findViewById<View>(R.id.share_password_as_plaintext) == null)
            return

        val sendIntent = Intent()
        sendIntent.action = Intent.ACTION_SEND
        sendIntent.putExtra(Intent.EXTRA_TEXT, passwordEntry?.password)
        sendIntent.type = "text/plain"
        startActivity(
            Intent.createChooser(
                sendIntent,
                resources.getText(R.string.send_plaintext_password_to)
            )
        ) // Always show a picker to give the user a chance to cancel
    }

    private fun setTimer() {
        // make sure to cancel any running tasks as soon as possible
        // if the previous task is still running, do not ask it to clear the password
        delayTask?.cancelAndSignal(true)

        // launch a new one
        delayTask = DelayShow()
        delayTask?.execute()
    }

    /**
     * Gets a relative string describing when this shape was last changed
     * (e.g. "one hour ago")
     */
    private fun getLastChangedString(timeStamp: Long): CharSequence {
        if (timeStamp < 0) {
            throw RuntimeException()
        }

        return DateUtils.getRelativeTimeSpanString(this, timeStamp, true)
    }

    @Suppress("StaticFieldLeak")
    inner class DelayShow {

        private var skip = false
        private var service: Intent? = null
        private var showTime: Int = 0

        // Custom cancellation that can be triggered from another thread.
        //
        // This signals the DelayShow task to stop and avoids it having
        // to poll the AsyncTask.isCancelled() excessively. If skipClearing
        // is true, the cancelled task won't clear the clipboard.
        fun cancelAndSignal(skipClearing: Boolean) {
            skip = skipClearing
            if (service != null) {
                stopService(service)
                service = null
            }
        }

        fun execute() {
            service = Intent(this@PgpActivity, ClipboardService::class.java).also {
                it.action = ACTION_START
            }
            doOnPreExecute()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service)
            } else {
                startService(service)
            }
        }

        private fun doOnPreExecute() {
            showTime = try {
                Integer.parseInt(settings.getString("general_show_time", "45") as String)
            } catch (e: NumberFormatException) {
                45
            }
        }
    }

    companion object {
        const val OPEN_PGP_BOUND = 101
        const val REQUEST_KEY_ID = 203

        private const val ACTION_CLEAR = "ACTION_CLEAR_CLIPBOARD"
        private const val ACTION_START = "ACTION_START_CLIPBOARD_TIMER"

        const val TAG = "PgpActivity"

        const val KEY_PWGEN_TYPE_CLASSIC = "classic"
        const val KEY_PWGEN_TYPE_XKPASSWD = "xkpasswd"

        /**
         * Gets the relative path to the repository
         */
        fun getRelativePath(fullPath: String, repositoryPath: String): String =
            fullPath.replace(repositoryPath, "").replace("/+".toRegex(), "/")

        /**
         * Gets the Parent path, relative to the repository
         */
        fun getParentPath(fullPath: String, repositoryPath: String): String {
            val relativePath = getRelativePath(fullPath, repositoryPath)
            val index = relativePath.lastIndexOf("/")
            return "/${relativePath.substring(startIndex = 0, endIndex = index + 1)}/".replace("/+".toRegex(), "/")
        }

        /**
         * Gets the name of the password (excluding .gpg)
         */
        fun getName(fullPath: String): String {
            return FilenameUtils.getBaseName(fullPath)
        }

        /**
         * /path/to/store/social/facebook.gpg -> social/facebook
         */
        @JvmStatic
        fun getLongName(fullPath: String, repositoryPath: String, basename: String): String {
            var relativePath = getRelativePath(fullPath, repositoryPath)
            return if (relativePath.isNotEmpty() && relativePath != "/") {
                // remove preceding '/'
                relativePath = relativePath.substring(1)
                if (relativePath.endsWith('/')) {
                    relativePath + basename
                } else {
                    "$relativePath/$basename"
                }
            } else {
                basename
            }
        }
    }
}
