package com.example.captcha

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume


fun showCaptchaNotification(context: Context, quantidade: Int) {
    val channelId = "captcha_channel"
    val notificationId = 1001

    // Crie o canal de notificação (necessário para Android 8+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val name = "Captchas pendentes"
        val descriptionText = "Notificações de captchas por resolver"
        val importance = NotificationManager.IMPORTANCE_DEFAULT

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val channel = NotificationChannel(channelId, name, importance).apply {
            description = descriptionText
            setSound(soundUri, audioAttributes)
        }
        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    // Intent para abrir o app ao clicar na notificação
    val intent = Intent(context, MainActivity::class.java).apply {
        putExtra("isFromNotification", true)
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    // Crie a notificação
    val builder = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Captchas por resolver")
        .setContentText("Existem $quantidade captchas pendentes!")
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))

    try {
        with(NotificationManagerCompat.from(context)) {
            notify(notificationId, builder.build())
        }
    } catch (e: SecurityException) {
        // Permissão foi negada ou revogada
        // Mostre uma mensagem ao usuário ou ignore
    }

}

fun getUltimoNotificado(context: Context): Int {
    val prefs = context.getSharedPreferences("captcha_prefs", Context.MODE_PRIVATE)
    return prefs.getInt("ultimoTotalCaptchas", 0)
}

fun setUltimoNotificado(context: Context, valor: Int) {
    val prefs = context.getSharedPreferences("captcha_prefs", Context.MODE_PRIVATE)
    prefs.edit().putInt("ultimoTotalCaptchas", valor).apply()
}

fun salvarEstadoToggle(context: Context, key: String, valor: Boolean) {
    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    prefs.edit().putBoolean(key, valor).apply()
}

fun salvarEstadoAnteriorToggle(context: Context, key: String, valor: Boolean) {
    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    prefs.edit().putBoolean(key, valor).apply()
}

fun lerEstadoToggle(context: Context, key: String, default: Boolean = false): Boolean {
    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    return prefs.getBoolean(key, default)
}

fun lerEstadoAnteriorToggle(context: Context, key: String, default: Boolean = false): Boolean {
    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    return prefs.getBoolean(key, default)
}

data class JobItem(val jobId: String, val imageUrl: String)

class MainActivity : ComponentActivity() {
    private val serverUrl = "https://YOUR-DYNAMIC-DNS:5000"
    private var openedFromNotification = false
    val atualizarImediatoChannel = Channel<Unit>(Channel.CONFLATED)

    // Para disparar atualização imediata:
    private fun dispararAtualizacaoImediata() {
        // Envia o sinal, se canal estiver disponível
        atualizarImediatoChannel.trySend(Unit)
    }

    private val _automacaoAtiva = mutableStateOf(false)
    var automacaoAtiva: Boolean
        get() = _automacaoAtiva.value
        set(value) { _automacaoAtiva.value = value }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = this

        setContent {

            var notificationPermissionGranted by remember { mutableStateOf(false) }
            var jobs by remember { mutableStateOf(listOf<JobItem>()) }
            var isLoading by remember { mutableStateOf(false) }
            val lifecycleOwner = LocalLifecycleOwner.current

            // Mantém o ecrã ligado enquanto esta Activity está ativa
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            RequestNotificationPermission { granted ->
                notificationPermissionGranted = granted
            }

            // Observar eventos do lifecycle
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_PAUSE) {
                        Log.d("MainActivity", "ON_PAUSE chamado.")
                        salvarEstadoAnteriorToggle(context,"automacaoAnteriorAtiva", automacaoAtiva)
                        if (automacaoAtiva) { enviarFlagAutomacao(false) { } }
                        _automacaoAtiva.value = false
                        salvarEstadoToggle(context, "automacaoAtiva", false)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)

                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            // Função para buscar jobs e controlar o loading
            suspend fun atualizarJobs(): Unit = suspendCancellableCoroutine { cont ->
                fetchPendingJobs { result ->
                    jobs = result
                    isLoading = false
                    val pendentes = result.size
                    val ultimoNotificado = getUltimoNotificado(context)
                    // Mostra notificação se houver captchas pendentes
                    if (pendentes > ultimoNotificado && pendentes > 0 && notificationPermissionGranted) {
                        showCaptchaNotification(this@MainActivity, pendentes)
                    }
                    // Atualiza a variável de controlo (inclusive para zero)
                    setUltimoNotificado(context, pendentes)
                    cont.resume(Unit)
                }
            }

            // Polling automático
            LaunchedEffect(Unit) {
                while(true) {
                    val delayMillis = if (jobs.isEmpty()) 5000L else 60000L
                    select<Unit> {
                        onTimeout(delayMillis) {
                            Log.d("Polling", "Timeout expirado, atualizando jobs.")
                        }
                        atualizarImediatoChannel.onReceive {
                            Log.d("Polling", "Recebeu sinal para atualização imediata.")
                        }
                    }

                    if (automacaoAtiva) {
                        isLoading = true
                        atualizarJobs() // suspende novo polling até atualizacao estar concluida
                        isLoading = false
                    }
                }
            }

            // Atualiza sempre que o app ganha o foco
            OnAppResumed {
                Log.d("MainActivity", "onAppResumed setContent chamado. openedFromNotification = $openedFromNotification")
                if(automacaoAtiva){
                    lifecycleScope.launch {
                        atualizarJobs()
                    }
                }
            }

            @OptIn(ExperimentalMaterial3Api::class)
            @Composable
            fun MainScreen() {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Imagem de fundo preenchendo toda a área
                    Image(
                        painter = painterResource(id = R.drawable.fundo),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop // ajusta a escala de forma proporcional
                    )
                    MaterialTheme {
                        Scaffold(
                            containerColor = Color.Transparent,
                            topBar = {
                                TopAppBar(title = { Text("Resolução de Captcha") },
                                            colors = TopAppBarDefaults.topAppBarColors(
                                                containerColor = Color.Transparent
                                            ))
                            }
                        ) { innerPadding ->
                            Box(modifier = Modifier.padding(innerPadding)) {
                                Column {
                                    AutomationToggle(
                                        checked = automacaoAtiva,
                                        onCheckedChange = { ativo ->
                                            automacaoAtiva = ativo
                                            enviarFlagAutomacao(ativo) { sucesso ->
                                                // Aqui podes mostrar um Toast se quiseres, por ex.
                                            }
                                            salvarEstadoToggle(
                                                context,
                                                "automacaoAtiva",
                                                automacaoAtiva
                                            )
                                        },
                                        modifier = Modifier.padding(start = 16.dp, top = 12.dp)
                                    )
                                }

                                if (isLoading) {
                                    CircularProgressIndicator(modifier = Modifier.padding(32.dp))
                                } else {
                                    CaptchaJobList(
                                        jobs = jobs,
                                        onSendSolution = { jobId, solution ->
                                            sendCaptchaSolution(jobId, solution) { success ->
                                                if (success) {
                                                    Toast.makeText(
                                                        this@MainActivity,
                                                        "Resposta enviada!",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                    // Atualize a lista após envio
                                                    //atualizarJobs()
                                                    dispararAtualizacaoImediata()
                                                    //fetchPendingJobs { result -> jobs = result }
                                                } else {
                                                    Toast.makeText(
                                                        this@MainActivity,
                                                        "Erro ao enviar resposta",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            MainScreen()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        openedFromNotification = intent?.getBooleanExtra("isFromNotification", false) ?: false
        Log.d("MainActivity", "onResume chamado. openedFromNotification = $openedFromNotification")
        if (openedFromNotification){
            Log.d("MainActivity", "onResume recuperando estados anteriores")
            _automacaoAtiva.value = lerEstadoAnteriorToggle(this, "automacaoAnteriorAtiva", false)
            Log.d("MainActivity", "onResume Flag $automacaoAtiva")
            if (automacaoAtiva) { enviarFlagAutomacao(automacaoAtiva) { } }
        } else {
            Log.d("MainActivity", "onResume recuperando estados a falso")
            _automacaoAtiva.value = lerEstadoToggle(this, "automacaoAtiva", false)
            Log.d("MainActivity", "onResume Flag1 $automacaoAtiva")
        }
        openedFromNotification = false
        Log.d("MainActivity", "onResume finalizado. openedFromNotification = $openedFromNotification")
    }


    // Função para buscar jobs pendentes
    private fun fetchPendingJobs(onResult: (List<JobItem>) -> Unit) {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("$serverUrl/pending_jobs")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(emptyList())
            }
            override fun onResponse(call: Call, response: Response) {
                val jobs = mutableListOf<JobItem>()
                response.body?.string()?.let { body ->
                    val array = JSONArray(body)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        jobs.add(
                            JobItem(
                                jobId = obj.getString("job_id"),
                                imageUrl = serverUrl + obj.getString("image_url")
                            )
                        )
                    }
                }
                runOnUiThread { onResult(jobs) }
            }
        })
    }

    // Função para enviar a solução do captcha
    private fun sendCaptchaSolution(jobId: String, solution: String, onResult: (Boolean) -> Unit) {
        val client = OkHttpClient()
        val json = JSONObject().put("captcha", solution).toString()
        val body = RequestBody.create("application/json".toMediaTypeOrNull(), json)
        val request = Request.Builder()
            .url("$serverUrl/captcha_result/$jobId")
            .post(body)
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { onResult(false) }
            }
            override fun onResponse(call: Call, response: Response) {
                runOnUiThread { onResult(response.isSuccessful) }
            }
        })
    }

    private fun enviarFlagAutomacao(ativo: Boolean, onResult: (Boolean) -> Unit) {
        val client = OkHttpClient()
        val url = "$serverUrl/automation_flag" // ajusta para o teu server real

        val json = """{"automation_active": $ativo}"""
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        Thread {
            try {
                val response = client.newCall(request).execute()
                onResult(response.isSuccessful)
            } catch (e: Exception) {
                onResult(false)
            }
        }.start()
    }

    

}

@Composable
fun RequestNotificationPermission(onPermissionResult: (Boolean) -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> onPermissionResult(granted) }
    )

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionCheck = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            )
            if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onPermissionResult(true)
            }
        } else {
            onPermissionResult(true)
        }
    }
}

@Composable
fun OnAppResumed(onResume: () -> Unit) {
    Log.d("MainActivity", "onAppResumed @Composable chamado.")
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

@Composable
fun AutomationToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Text(
            if (checked) "Automações ativas" else "Automações suspensas",
            Modifier.padding(end = 8.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}


@Composable
fun CaptchaJobList(
    jobs: List<JobItem>,
    onSendSolution: (jobId: String, solution: String) -> Unit
) {
    LazyColumn {
        items(jobs) { job ->
            var solution by remember { mutableStateOf("") }
            Card(
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxWidth(),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Job: ${job.jobId}", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    AsyncImage(
                        model = job.imageUrl,
                        contentDescription = "Captcha",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = solution,
                        onValueChange = { solution = it },
                        label = { Text("Digite o captcha") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            if (solution.isNotBlank()) {
                                onSendSolution(job.jobId, solution)
                            }
                        },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text("Enviar Resposta")
                    }
                }
            }
        }
    }
}