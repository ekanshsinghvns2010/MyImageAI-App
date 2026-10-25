package ai.myimage.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                MyImageAIScreen()
            }
        }
    }
}

@Composable
fun MyImageAIScreen() {
    var prompt by remember { mutableStateOf("") }
    var negativePrompt by remember { mutableStateOf("") }
    var backendUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var message by remember {
        mutableStateOf("Enter your backend details to get started.")
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("MyImageAI", style = MaterialTheme.typography.headlineLarge)
            Text("AI image generation", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Describe your image") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            OutlinedTextField(
                value = negativePrompt,
                onValueChange = { negativePrompt = it },
                label = { Text("Negative prompt (optional)") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = backendUrl,
                onValueChange = { backendUrl = it },
                label = { Text("Backend HTTPS URL") },
                placeholder = { Text("https://your-server.example") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("Client API key") },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    message = when {
                        prompt.isBlank() ->
                            "Please enter an image prompt."
                        !backendUrl.startsWith("https://") ->
                            "Enter a valid HTTPS backend URL."
                        apiKey.isBlank() ->
                            "Enter your client API key."
                        else ->
                            "Form validated. Image generation will be connected next."
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Generate Image")
            }

            Text(message, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Your backend must be deployed and configured with an image-generation provider.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
