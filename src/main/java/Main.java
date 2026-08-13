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
                        Map.of(
                                "file_path", Map.of(
                                        "type", "string",
                                        "description", "The path to the file to read"
                                )
                        )
                ))
                .putAdditionalProperty("required", JsonValue.from(List.of("file_path")))
                .build();

        FunctionDefinition readFunction = FunctionDefinition.builder()
                .name("Read")
                .description("Read and return the contents of a file")
                .parameters(readParameters)
                .build();

        ChatCompletionTool readTool = ChatCompletionTool.builder()
                .function(readFunction)
                .build();

        ChatCompletion response = client.chat().completions().create(
                ChatCompletionCreateParams.builder()
                        .model("anthropic/claude-haiku-4.5")
                        .addUserMessage(prompt)
                        .addTool(readTool)
                        .build()
        );

        if (response.choices().isEmpty()) {
            throw new RuntimeException("no choices in response");
        }

        System.err.println("Logs from your program will appear here!");

        // ---- Handle tool calls ----
        var message = response.choices().get(0).message();

        List<ChatCompletionMessageToolCall> toolCalls = message.toolCalls().orElse(List.of());

        if (!toolCalls.isEmpty()) {
            ChatCompletionMessageToolCall toolCall = toolCalls.get(0);
            String functionName = toolCall.function().name();
            String argumentsJson = toolCall.function().arguments();

            if ("Read".equals(functionName)) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode argsNode = mapper.readTree(argumentsJson);
                String filePath = argsNode.get("file_path").asText();

                String fileContents = Files.readString(Path.of(filePath));
                System.out.print(fileContents);
            }
        } else {
            System.out.print(message.content().orElse(""));
        }
    }
}