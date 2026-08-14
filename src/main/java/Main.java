import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;

public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !"-p".equals(args[0])) {
            System.err.println("Usage: program -p <prompt>");
            System.exit(1);
        }

        String prompt = args[1];

        String apiKey = System.getenv("OPENROUTER_API_KEY");
        String baseUrl = System.getenv("OPENROUTER_BASE_URL");
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "https://openrouter.ai/api/v1";
        }

        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("OPENROUTER_API_KEY is not set");
        }

        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        // ---- Read tool ----
        FunctionParameters readParameters = FunctionParameters.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(
                        Map.of("file_path", Map.of(
                                "type", "string",
                                "description", "The path to the file to read"
                        ))
                ))
                .putAdditionalProperty("required", JsonValue.from(List.of("file_path")))
                .build();

        ChatCompletionTool readTool = ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("Read")
                        .description("Read and return the contents of a file")
                        .parameters(readParameters)
                        .build())
                .build();

        // ---- Write tool ----
        FunctionParameters writeParameters = FunctionParameters.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(
                        Map.of(
                                "file_path", Map.of("type", "string", "description", "The path of the file to write to"),
                                "content", Map.of("type", "string", "description", "The content to write to the file")
                        )
                ))
                .putAdditionalProperty("required", JsonValue.from(List.of("file_path", "content")))
                .build();

        ChatCompletionTool writeTool = ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("Write")
                        .description("Write content to a file")
                        .parameters(writeParameters)
                        .build())
                .build();

        // ---- Bash tool ----
        FunctionParameters bashParameters = FunctionParameters.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(
                        Map.of("command", Map.of(
                                "type", "string",
                                "description", "The command to execute"
                        ))
                ))
                .putAdditionalProperty("required", JsonValue.from(List.of("command")))
                .build();

        ChatCompletionTool bashTool = ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("Bash")
                        .description("Execute a shell command")
                        .parameters(bashParameters)
                        .build())
                .build();

        ObjectMapper mapper = new ObjectMapper();

        // ---- Conversation history ----
        ChatCompletionCreateParams.Builder requestBuilder = ChatCompletionCreateParams.builder()
                .model("anthropic/claude-haiku-4.5")
                .addTool(readTool)
                .addTool(writeTool)
                .addTool(bashTool)
                .addUserMessage(prompt);

        // ---- Agent loop ----
        while (true) {
            ChatCompletion response = client.chat().completions().create(requestBuilder.build());

            if (response.choices().isEmpty()) {
                throw new RuntimeException("no choices in response");
            }

            var message = response.choices().get(0).message();
            requestBuilder.addMessage(message.toParam());

            List<ChatCompletionMessageToolCall> toolCalls = message.toolCalls().orElse(List.of());

            if (toolCalls.isEmpty()) {
                System.err.println("Final response received, exiting loop.");
                System.out.print(message.content().orElse(""));
                break;
            }

            for (ChatCompletionMessageToolCall toolCall : toolCalls) {
                String functionName = toolCall.function().name();
                String argumentsJson = toolCall.function().arguments();
                String toolCallId = toolCall.id();

                String result;

                try {
                    JsonNode argsNode = mapper.readTree(argumentsJson);

                    if ("Read".equals(functionName)) {
                        String filePath = argsNode.get("file_path").asText();
                        result = Files.readString(Path.of(filePath));

                    } else if ("Write".equals(functionName)) {
                        String filePath = argsNode.get("file_path").asText();
                        String content = argsNode.get("content").asText();
                        Path path = Path.of(filePath);
                        if (path.getParent() != null) {
                            Files.createDirectories(path.getParent());
                        }
                        Files.writeString(path, content);
                        result = "File written successfully to " + filePath;

                    } else if ("Bash".equals(functionName)) {
                        String command = argsNode.get("command").asText();
                        result = executeBashCommand(command);

                    } else {
                        result = "Unknown tool: " + functionName;
                    }
                } catch (Exception e) {
                    result = "Error executing tool: " + e.getMessage();
                }

                System.err.println("Executed tool: " + functionName);

                requestBuilder.addMessage(
                        ChatCompletionToolMessageParam.builder()
                                .toolCallId(toolCallId)
                                .content(result)
                                .build()
                );
            }
        }
    }

    private static String executeBashCommand(String command) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder("sh", "-c", command);
        processBuilder.directory(Path.of("").toAbsolutePath().toFile()); // current working directory
        processBuilder.redirectErrorStream(true); // stdout + stderr dono ek saath capture honge

        Process process = processBuilder.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        }

        process.waitFor();

        return output.toString();
    }
}