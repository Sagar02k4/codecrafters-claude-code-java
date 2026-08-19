import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

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

    static OpenAIClient client;
    static ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("OPENROUTER_API_KEY");
        String baseUrl = System.getenv("OPENROUTER_BASE_URL");
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "https://openrouter.ai/api/v1";
        }
        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("OPENROUTER_API_KEY is not set");
        }

        client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        List<ChatCompletionTool> tools = buildTools();

        // ---- Agar -p flag diya hai, to single-shot mode ----
        if (args.length >= 2 && "-p".equals(args[0])) {
            String prompt = args[1];
            ChatCompletionCreateParams.Builder requestBuilder = ChatCompletionCreateParams.builder()
                    .model("nvidia/nemotron-3-ultra-550b-a55b:free")
                    .addSystemMessage("STRICT RULE: Call Read exactly ONCE for the requested file, then STOP calling tools and respond with the file's content as plain text. Do NOT read any other file. Do NOT explore the codebase. Violating this rule is not allowed.");
            for (ChatCompletionTool tool : tools) requestBuilder.addTool(tool);
            requestBuilder.addUserMessage(prompt);

            String finalAnswer = runAgentLoop(requestBuilder);
            System.out.print(finalAnswer);
            return;
        }

        // ---- Nahi to interactive chat mode ----
        System.err.println("Entering chat mode. Type 'exit' or 'quit' to stop.");
        Scanner scanner = new Scanner(System.in);

        ChatCompletionCreateParams.Builder requestBuilder = ChatCompletionCreateParams.builder()
                .model("nvidia/nemotron-3-ultra-550b-a55b:free")
                .addSystemMessage("STRICT RULE: Call Read exactly ONCE for the requested file, then STOP calling tools and respond with the file's content as plain text. Do NOT read any other file. Do NOT explore the codebase. Violating this rule is not allowed.");

        for (ChatCompletionTool tool : tools) requestBuilder.addTool(tool);

        while (true) {
            System.out.print("> ");
            if (!scanner.hasNextLine()) break;
            String userInput = scanner.nextLine();

            if (userInput.equalsIgnoreCase("exit") || userInput.equalsIgnoreCase("quit")) {
                System.err.println("Exiting chat.");
                break;
            }
            if (userInput.isBlank()) continue;

            requestBuilder.addUserMessage(userInput);

            String finalAnswer = runAgentLoop(requestBuilder);
            System.out.println(finalAnswer);
        }

        scanner.close();
    }

    // ---- Agent loop: ek user message ke baad jab tak tool calls khatam na ho jaayein ----
    private static String runAgentLoop(ChatCompletionCreateParams.Builder requestBuilder) throws Exception {
    int maxIterations = 8;
    int iterations = 0;

    while (true) {
        iterations++;
        if (iterations > maxIterations) {
            return "Error: exceeded max iterations without a final answer.";
        }

        ChatCompletion response = client.chat().completions().create(requestBuilder.build());

        if (response.choices().isEmpty()) {
            throw new RuntimeException("no choices in response");
        }

        var message = response.choices().get(0).message();
        requestBuilder.addMessage(message.toParam());

        List<ChatCompletionMessageToolCall> toolCalls = message.toolCalls().orElse(List.of());

        if (toolCalls.isEmpty()) {
            return message.content().orElse("");
        }

        for (ChatCompletionMessageToolCall toolCall : toolCalls) {
            String functionName = toolCall.function().name();
            String argumentsJson = toolCall.function().arguments();
            String toolCallId = toolCall.id();

            String result = executeTool(functionName, argumentsJson);
            System.err.println("Executed tool: " + functionName + " args=" + argumentsJson + " -> " + result.substring(0, Math.min(100, result.length())));

            requestBuilder.addMessage(
                    ChatCompletionToolMessageParam.builder()
                            .toolCallId(toolCallId)
                            .content(result)
                            .build()
            );
        }
    }
}

    // ---- Tool execution ----
    private static String executeTool(String functionName, String argumentsJson) {
        try {
            JsonNode argsNode = mapper.readTree(argumentsJson);

            switch (functionName) {
                case "Read": {
                    String filePath = argsNode.get("file_path").asText();
                    return Files.readString(Path.of(filePath));
                }
                case "Write": {
                    String filePath = argsNode.get("file_path").asText();
                    String content = argsNode.get("content").asText();
                    Path path = Path.of(filePath);
                    if (path.getParent() != null) {
                        Files.createDirectories(path.getParent());
                    }
                    Files.writeString(path, content);
                    return "File written successfully to " + filePath;
                }
                case "Edit": {
                    String filePath = argsNode.get("file_path").asText();
                    String oldString = argsNode.get("old_string").asText();
                    String newString = argsNode.get("new_string").asText();

                    String fileContent = Files.readString(Path.of(filePath));

                    int firstIndex = fileContent.indexOf(oldString);
                    if (firstIndex == -1) {
                        return "Error: old_string not found in file " + filePath;
                    }
                    int lastIndex = fileContent.lastIndexOf(oldString);
                    if (firstIndex != lastIndex) {
                        return "Error: old_string appears multiple times in file, must be unique";
                    }

                    String updatedContent = fileContent.substring(0, firstIndex)
                            + newString
                            + fileContent.substring(firstIndex + oldString.length());

                    Files.writeString(Path.of(filePath), updatedContent);
                    return "File edited successfully: " + filePath;
                }
                case "Bash": {
                    String command = argsNode.get("command").asText();
                    return executeBashCommand(command);
                }
                default:
                    return "Unknown tool: " + functionName;
            }
        } catch (Exception e) {
            return "Error executing tool: " + e.getMessage();
        }
    }

    private static String executeBashCommand(String command) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder("sh", "-c", command);
        processBuilder.directory(Path.of("").toAbsolutePath().toFile());
        processBuilder.redirectErrorStream(true);

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

    // ---- Tool definitions ----
    private static List<ChatCompletionTool> buildTools() {
        ChatCompletionTool readTool = ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("Read")
                        .description("Read and return the contents of a file")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(
                                        Map.of("file_path", Map.of(
                                                "type", "string",
                                                "description", "The path to the file to read"
                                        ))
                                ))
                                .putAdditionalProperty("required", JsonValue.from(List.of("file_path")))
                                .build())
                        .build())
                .build();

        ChatCompletionTool writeTool = ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("Write")
                        .description("Write content to a file")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(
                                        Map.of(
                                                "file_path", Map.of("type", "string", "description", "The path of the file to write to"),
                                                "content", Map.of("type", "string", "description", "The content to write to the file")
                                        )
                                ))
                                .putAdditionalProperty("required", JsonValue.from(List.of("file_path", "content")))
                                .build())
                        .build())
                .build();

        ChatCompletionTool editTool = ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("Edit")
                        .description("Replace an exact, unique occurrence of text within a file. Use this instead of Write when you only need to change part of a file.")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(
                                        Map.of(
                                                "file_path", Map.of("type", "string", "description", "The path of the file to edit"),
                                                "old_string", Map.of("type", "string", "description", "The exact text to find and replace (must be unique in the file)"),
                                                "new_string", Map.of("type", "string", "description", "The text to replace it with")
                                        )
                                ))
                                .putAdditionalProperty("required", JsonValue.from(List.of("file_path", "old_string", "new_string")))
                                .build())
                        .build())
                .build();

        ChatCompletionTool bashTool = ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("Bash")
                        .description("Execute a shell command")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(
                                        Map.of("command", Map.of(
                                                "type", "string",
                                                "description", "The command to execute"
                                        ))
                                ))
                                .putAdditionalProperty("required", JsonValue.from(List.of("command")))
                                .build())
                        .build())
                .build();

        return List.of(readTool, writeTool, editTool, bashTool);
    }
}