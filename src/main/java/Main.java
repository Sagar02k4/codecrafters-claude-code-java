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

        // ---- Read tool define karo ----
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

        ObjectMapper mapper = new ObjectMapper();

        // ---- Conversation history: yahi hamara "messages" array hai ----
        ChatCompletionCreateParams.Builder requestBuilder = ChatCompletionCreateParams.builder()
                .model("anthropic/claude-haiku-4.5")
                .addTool(readTool)
                .addUserMessage(prompt);

        // ---- Agent loop shuru ----
        while (true) {
            ChatCompletionCreateParams request = requestBuilder.build();

            ChatCompletion response = client.chat().completions().create(request);

            if (response.choices().isEmpty()) {
                throw new RuntimeException("no choices in response");
            }

            var choice = response.choices().get(0);
            var message = choice.message();

            // Assistant ka response history mein add karo
            requestBuilder.addMessage(message.toParam());

            List<ChatCompletionMessageToolCall> toolCalls = message.toolCalls().orElse(List.of());

            if (toolCalls.isEmpty()) {
                // Koi tool call nahi -> final answer mil gaya
                System.err.println("Final response received, exiting loop.");
                System.out.print(message.content().orElse(""));
                break;
            }

            // Har tool call ko execute karo
            for (ChatCompletionMessageToolCall toolCall : toolCalls) {
                String functionName = toolCall.function().name();
                String argumentsJson = toolCall.function().arguments();
                String toolCallId = toolCall.id();

                String result;

                if ("Read".equals(functionName)) {
                    try {
                        JsonNode argsNode = mapper.readTree(argumentsJson);
                        String filePath = argsNode.get("file_path").asText();
                        result = Files.readString(Path.of(filePath));
                    } catch (Exception e) {
                        result = "Error reading file: " + e.getMessage();
                    }
                } else {
                    result = "Unknown tool: " + functionName;
                }

                System.err.println("Executed tool: " + functionName + " -> result length: " + result.length());

                // Tool ka result history mein add karo (role: "tool")
                requestBuilder.addMessage(
                        ChatCompletionToolMessageParam.builder()
                                .toolCallId(toolCallId)
                                .content(result)
                                .build()
                );
            }

            // loop phir se chalega, is baar naye messages ke saath
        }
    }
}