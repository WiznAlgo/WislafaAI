package id.winatra.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var db: KnowledgeDb
    private lateinit var adapter: ChatAdapter
    private lateinit var pages: List<View>

    // chat
    private lateinit var rvChat: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private var busy = false

    // knowledge
    private lateinit var tvKnowCount: TextView
    private lateinit var lvKnowledge: ListView
    private var knowledge: List<Knowledge> = emptyList()

    // model
    private lateinit var tvModelStatus: TextView
    private lateinit var etUrl: EditText
    private lateinit var btnDownload: Button
    private lateinit var btnPickFile: Button
    private lateinit var pbModel: ProgressBar
    private lateinit var tvProgress: TextView
    private var modelJob: Job? = null

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) toast("Izin notifikasi ditolak — notifikasi tidak akan muncul")
        }

    private val pickModel =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) runModelTask { progress -> ModelManager.importFromUri(applicationContext, uri, progress) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = KnowledgeDb.get(this)

        // Edge-to-edge (targetSdk 35): beri ruang untuk status bar, navbar & keyboard
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }

        setupTabs()
        setupChat()
        setupKnowledge()
        setupModel()
    }

    override fun onResume() {
        super.onResume()
        refreshModelStatus()
        refreshKnowledge()
        if (!busy) loadHistory()   // sinkron dengan percakapan lewat notifikasi
    }

    // ================= Tabs =================

    private fun setupTabs() {
        pages = listOf(
            findViewById(R.id.pageChat), findViewById(R.id.pageKnowledge), findViewById(R.id.pageModel)
        )
        val tabs = findViewById<TabLayout>(R.id.tabs)
        listOf("Chat", "Knowledge", "Model").forEach { tabs.addTab(tabs.newTab().setText(it)) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showPage(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        // Belum ada model -> langsung ke tab Model
        val start = if (LlmEngine.modelFile(this) == null) 2 else 0
        showPage(start)
        tabs.getTabAt(start)?.select()
    }

    private fun showPage(pos: Int) {
        pages.forEachIndexed { i, p -> p.visibility = if (i == pos) View.VISIBLE else View.GONE }
    }

    // ================= Chat =================

    private fun setupChat() {
        rvChat = findViewById(R.id.rvChat)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        adapter = ChatAdapter()
        rvChat.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        rvChat.adapter = adapter
        btnSend.setOnClickListener { send() }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            ContextCompat.startForegroundService(this, Intent(this, AIForegroundService::class.java))
            toast("Service aktif — lihat notifikasi")
        }
        findViewById<Button>(R.id.btnCapture).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            ContextCompat.startForegroundService(this, Intent(this, AIForegroundService::class.java))
            // Dialog izin dari sistem: pilih "Mulai sekarang" / "Seluruh layar"
            startActivity(Intent(this, ScreenCaptureActivity::class.java))
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            startService(Intent(this, AIForegroundService::class.java).setAction(AIForegroundService.ACTION_STOP))
            toast("Service dinonaktifkan")
        }
    }

    private fun loadHistory() {
        adapter.setAll(db.recentMemory(40).map { ChatMsg(it.role == "user", it.text) })
        if (adapter.itemCount > 0) rvChat.scrollToPosition(adapter.itemCount - 1)
    }

    private fun send() {
        val q = etInput.text.toString().trim()
        if (q.isEmpty() || busy) return
        etInput.text.clear()
        busy = true
        btnSend.isEnabled = false

        adapter.add(ChatMsg(true, q))
        val idx = adapter.add(ChatMsg(false, "⏳ Berpikir…"))
        rvChat.scrollToPosition(idx)

        lifecycleScope.launch {
            val reply = try {
                Responder.answer(applicationContext, q, onPartial = { partial ->
                    runOnUiThread {
                        adapter.items[idx].text = partial
                        adapter.notifyItemChanged(idx)
                        rvChat.scrollToPosition(idx)
                    }
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
            adapter.items[idx].text = reply
            adapter.notifyItemChanged(idx)
            rvChat.scrollToPosition(idx)
            busy = false
            btnSend.isEnabled = true
            refreshKnowledge()
        }
    }

    // ================= Knowledge =================

    private fun setupKnowledge() {
        tvKnowCount = findViewById(R.id.tvKnowCount)
        lvKnowledge = findViewById(R.id.lvKnowledge)
        val etTitle = findViewById<EditText>(R.id.etTitle)
        val etContent = findViewById<EditText>(R.id.etContent)

        findViewById<Button>(R.id.btnLearn).setOnClickListener {
            val t = etTitle.text.toString().trim()
            val c = etContent.text.toString().trim()
            if (t.isEmpty() || c.isEmpty()) { toast("Topik dan isi wajib diisi"); return@setOnClickListener }
            db.addKnowledge(t, c)
            etTitle.text.clear(); etContent.text.clear()
            refreshKnowledge(); toast("Tersimpan")
        }

        lvKnowledge.setOnItemClickListener { _, _, pos, _ ->
            val k = knowledge[pos]
            AlertDialog.Builder(this)
                .setTitle(k.title)
                .setMessage(k.content)
                .setPositiveButton("Tutup", null)
                .setNegativeButton("Hapus") { _, _ -> db.deleteKnowledge(k.id); refreshKnowledge() }
                .show()
        }

        findViewById<Button>(R.id.btnClearMemory).setOnClickListener {
            db.clearMemory(); adapter.setAll(emptyList()); toast("Memori chat dihapus")
        }
        findViewById<Button>(R.id.btnClearKnowledge).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Hapus semua knowledge?")
                .setMessage("Semua pengetahuan yang kamu ajarkan akan hilang.")
                .setPositiveButton("Hapus") { _, _ -> db.clearKnowledge(); refreshKnowledge() }
                .setNegativeButton("Batal", null)
                .show()
        }
    }

    private fun refreshKnowledge() {
        knowledge = db.listKnowledge()
        tvKnowCount.text = "Pengetahuan tersimpan: ${knowledge.size} (ketuk untuk lihat / hapus)"
        lvKnowledge.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, knowledge.map { it.title })
    }

    // ================= Model =================

    private fun setupModel() {
        tvModelStatus = findViewById(R.id.tvModelStatus)
        etUrl = findViewById(R.id.etUrl)
        btnDownload = findViewById(R.id.btnDownload)
        btnPickFile = findViewById(R.id.btnPickFile)
        pbModel = findViewById(R.id.pbModel)
        tvProgress = findViewById(R.id.tvProgress)

        etUrl.setText(ModelManager.DEFAULT_URL)
        findViewById<Button>(R.id.btnQwen05).setOnClickListener { etUrl.setText(ModelManager.DEFAULT_URL) }
        findViewById<Button>(R.id.btnQwen15).setOnClickListener { etUrl.setText(ModelManager.QWEN_1_5B_URL) }

        btnDownload.setOnClickListener {
            if (modelJob?.isActive == true) { modelJob?.cancel(); return@setOnClickListener }   // tombol = Batal
            val url = etUrl.text.toString().trim()
            if (!url.startsWith("https://")) { toast("URL harus diawali https://"); return@setOnClickListener }
            runModelTask { progress -> ModelManager.download(applicationContext, url, progress) }
        }
        btnPickFile.setOnClickListener { pickModel.launch(arrayOf("*/*")) }
    }

    private fun runModelTask(block: suspend ((Long, Long) -> Unit) -> String) {
        if (modelJob?.isActive == true) return
        btnDownload.text = "Batal"
        btnPickFile.isEnabled = false
        pbModel.visibility = View.VISIBLE
        pbModel.isIndeterminate = true

        modelJob = lifecycleScope.launch {
            try {
                val name = block { done, total -> runOnUiThread { showProgress(done, total) } }
                toast("Model terpasang: $name")
            } catch (e: CancellationException) {
                toast("Dibatalkan")
            } catch (e: Exception) {
                toast("Gagal: ${e.message}")
            } finally {
                btnDownload.text = "Unduh Model"
                btnPickFile.isEnabled = true
                pbModel.visibility = View.GONE
                tvProgress.text = ""
                refreshModelStatus()
            }
        }
    }

    private fun showProgress(done: Long, total: Long) {
        if (total > 0) {
            pbModel.isIndeterminate = false
            pbModel.max = 100
            pbModel.progress = (done * 100 / total).toInt()
            tvProgress.text = "${done shr 20} / ${total shr 20} MB"
        } else {
            tvProgress.text = "${done shr 20} MB"
        }
    }

    private fun refreshModelStatus() {
        val f = LlmEngine.modelFile(this)
        tvModelStatus.text = if (f != null) "🧠 Terpasang: ${f.name} (${f.length() shr 20} MB)"
        else "⚠️ Model belum terpasang"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
