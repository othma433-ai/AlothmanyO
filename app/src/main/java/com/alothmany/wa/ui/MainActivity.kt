package com.alothmany.wa.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.alothmany.wa.WaApplication
import com.alothmany.wa.accessibility.AutomationRuntime
import com.alothmany.wa.data.ActionRepository
import com.alothmany.wa.databinding.ActivityMainBinding
import com.alothmany.wa.domain.ActionTargetParser
import com.alothmany.wa.export.ExportFormat
import com.alothmany.wa.export.ExportManager
import com.alothmany.wa.export.ActionReportExporter
import com.alothmany.wa.service.AutomationForegroundService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val db get() = (application as WaApplication).database
    private var pendingExport = ExportFormat.CSV
    private var latestActionJobId: String? = null

    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            runCatching { ExportManager(contentResolver, db).exportLinks(uri, pendingExport) }
                .onSuccess { Toast.makeText(this@MainActivity, "تم التصدير", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(this@MainActivity, "فشل التصدير: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }


    private val createActionReport = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val jobId = latestActionJobId ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            runCatching { ActionReportExporter(contentResolver, db).exportCsv(uri, jobId) }
                .onSuccess { Toast.makeText(this@MainActivity, "تم تصدير تقرير Action", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(this@MainActivity, "فشل تقرير Action: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private val requestNotification = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.spinnerPackage.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("WhatsApp الشخصي", "WhatsApp Business", "Custom / Clone"))
        binding.spinnerExport.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            ExportFormat.entries.map { it.name })

        binding.btnAccessibility.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        binding.btnSync.setOnClickListener { launchWhatsAppThenCommand(AutomationForegroundService.ACTION_SYNC) }
        binding.btnDeep.setOnClickListener { launchWhatsAppThenCommand(AutomationForegroundService.ACTION_START_DEEP) }
        binding.btnNew.setOnClickListener { launchWhatsAppThenCommand(AutomationForegroundService.ACTION_START_NEW) }
        binding.btnScanInvites.setOnClickListener { createInviteJob("SCAN_INVITES") }
        binding.btnJoinInvites.setOnClickListener { createInviteJob("JOIN") }
        binding.btnPublish.setOnClickListener { createPublishJob() }
        binding.btnExportActions.setOnClickListener {
            val jobId = latestActionJobId
            if (jobId == null) Toast.makeText(this, "لا يوجد Action Job لتصديره", Toast.LENGTH_LONG).show()
            else createActionReport.launch("wa-action-$jobId.csv")
        }
        binding.btnPause.setOnClickListener { command(AutomationForegroundService.ACTION_PAUSE, withPackage = false) }
        binding.btnResume.setOnClickListener { command(AutomationForegroundService.ACTION_RESUME, withPackage = false) }
        binding.btnStop.setOnClickListener { command(AutomationForegroundService.ACTION_STOP, withPackage = false) }
        binding.btnRecover.setOnClickListener { command(AutomationForegroundService.ACTION_RECOVER, withPackage = false) }
        binding.txtDiagnostics.setOnLongClickListener {
            command(AutomationForegroundService.ACTION_CAPTURE_DIAGNOSTIC, withPackage = false)
            Toast.makeText(this, "جارٍ حفظ Diagnostic Replay", Toast.LENGTH_SHORT).show()
            true
        }
        binding.btnExport.setOnClickListener {
            pendingExport = ExportFormat.entries[binding.spinnerExport.selectedItemPosition]
            createDocument.launch("wa-links-${System.currentTimeMillis()}.${pendingExport.extension}")
        }

        if (Build.VERSION.SDK_INT >= 33) requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
        observeState()
    }

    private fun selectedPackage(): String = when (binding.spinnerPackage.selectedItemPosition) {
        1 -> "com.whatsapp.w4b"
        2 -> binding.editCustomPackage.text?.toString()?.trim().orEmpty()
        else -> "com.whatsapp"
    }

    private fun validatedPackage(): String? {
        val target = selectedPackage()
        if (target.isBlank() || !target.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) {
            Toast.makeText(this, "أدخل package صحيح للنسخة المخصصة", Toast.LENGTH_LONG).show()
            return null
        }
        return target
    }

    private fun createInviteJob(type: String) {
        val targetPackage = validatedPackage() ?: return
        val invites = ActionTargetParser.invites(binding.editInviteLinks.text?.toString().orEmpty())
        if (invites.isEmpty()) {
            Toast.makeText(this, "أدخل روابط دعوات WhatsApp صحيحة", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val jobId = ActionRepository(db).createJob(type, targetPackage, invites)
            launchTargetPackage(targetPackage)
            runActionJob(jobId)
        }
    }

    private fun createPublishJob() {
        val targetPackage = validatedPackage() ?: return
        val groups = ActionTargetParser.lines(binding.editPublishGroups.text?.toString().orEmpty())
        val message = binding.editPublishMessage.text?.toString().orEmpty().trim()
        if (groups.isEmpty() || message.isBlank()) {
            Toast.makeText(this, "أدخل أسماء القروبات والرسالة", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val jobId = ActionRepository(db).createJob("PUBLISH", targetPackage, groups, message)
            launchTargetPackage(targetPackage)
            runActionJob(jobId)
        }
    }

    private fun runActionJob(jobId: String) {
        val intent = Intent(this, AutomationForegroundService::class.java)
            .setAction(AutomationForegroundService.ACTION_RUN_ACTION_JOB)
            .putExtra(AutomationForegroundService.EXTRA_ACTION_JOB_ID, jobId)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun launchWhatsAppThenCommand(action: String) {
        val target = validatedPackage() ?: return
        val serviceIntent = Intent(this, AutomationForegroundService::class.java)
            .setAction(action)
            .putExtra(AutomationForegroundService.EXTRA_PACKAGE, target)
        ContextCompat.startForegroundService(this, serviceIntent)
        launchTargetPackage(target)
    }

    private fun launchTargetPackage(target: String) {
        packageManager.getLaunchIntentForPackage(target)?.let { launch ->
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    private fun command(action: String, withPackage: Boolean = true) {
        val intent = Intent(this, AutomationForegroundService::class.java).setAction(action)
        if (withPackage) {
            val target = validatedPackage() ?: return
            intent.putExtra(AutomationForegroundService.EXTRA_PACKAGE, target)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AutomationRuntime.connection.collectLatest { connected ->
                        binding.txtAccessibility.text = if (connected) "Accessibility: متصل" else "Accessibility: غير متصل"
                    }
                }
                launch {
                    db.jobDao().latestFlow().collectLatest { job ->
                        binding.txtJob.text = "Extraction Job: ${job?.status ?: "—"} ${job?.mode ?: ""}"
                        binding.txtCounters.text = "Links: ${job?.linksFound ?: 0} | Occurrences: ${job?.occurrences ?: 0}"
                    }
                }
                launch {
                    db.actionJobDao().latestFlow().collectLatest { job ->
                        latestActionJobId = job?.id
                        binding.txtActionJob.text = if (job == null) {
                            "Action Job: —"
                        } else {
                            "Action Job: ${job.type} ${job.status} | ${job.completedItems}/${job.totalItems} | failed ${job.failedItems}"
                        }
                    }
                }
                launch {
                    db.diagnosticDao().latestFlow(4).collectLatest { events ->
                        binding.txtDiagnostics.text = if (events.isEmpty()) {
                            "Diagnostics: —"
                        } else {
                            "Diagnostics:\n" + events.joinToString("\n") { "${it.severity} ${it.code}: ${it.message.take(90)}" }
                        }
                    }
                }
            }
        }
    }
}
