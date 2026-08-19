# Build Your Own Claude Code — Java

A minimal AI coding agent built in Java, inspired by [CodeCrafters' "Build Your Own Claude Code"](https://codecrafters.io/) challenge. It connects to an LLM (via [OpenRouter](https://openrouter.ai)) and gives the model the ability to read files, write files, edit files, and run shell commands — all driven by an autonomous agent loop.

## What it does

The program sends a prompt to an LLM along with a set of tool definitions. If the model decides it needs to use a tool to answer, the program executes that tool locally, feeds the result back to the model, and repeats — until the model responds with a final answer instead of another tool call.

This is the same core pattern used by tools like Claude Code, Cursor, and GitHub Copilot's agent mode: **LLM + tools + a loop**.

## Features

- **Agent loop** — automatically calls the model repeatedly, executing tools until a final answer is produced (with a max-iteration safety cap to avoid runaway loops)
- **Four tools:**
  - `Read` — read the contents of a file
  - `Write` — create or overwrite a file
  - `Edit` — find-and-replace a unique string within a file (surgical edits, instead of rewriting the whole file)
  - `Bash` — execute a shell command and capture its output
- **Two modes:**
  - Single-shot: `./your_program.sh -p "your prompt here"`
  - Interactive chat: `./your_program.sh` (conversation history persists across turns until you type `exit`)

## Setup

1. Get an API key from [OpenRouter](https://openrouter.ai) (free tier available for select models).
2. Set the following environment variables:

   ```bash
   export OPENROUTER_API_KEY="your-key-here"
   export OPENROUTER_BASE_URL="https://openrouter.ai/api/v1"   # optional, this is the default
   ```

3. Build and run:

   ```bash
   mvn clean compile
   ./your_program.sh -p "What files are in this directory?"
   ```

   Or start an interactive session:

   ```bash
   ./your_program.sh
   > Read README.md and summarize it
   > exit
   ```

## How it works

```
messages = [user prompt]

loop:
    response = call_llm(messages, tools)
    append response to messages

    if response has no tool calls:
        print response and stop

    for each tool call:
        result = execute_tool(tool call)
        append result to messages as a "tool" message
```

The full conversation history (including every tool call and its result) is sent back to the model on each iteration, so it always has full context of what it has already tried.

## A note on model choice

This project defaults to a free-tier model (`nvidia/nemotron-3-ultra-550b-a55b:free` via OpenRouter) to keep it cost-free to run. Worth knowing if you swap models:

- **Free/smaller models are less reliable at following instructions.** During development, the default model would sometimes explore the codebase or run unrelated commands even when given a narrow, specific task. This was mitigated with a strict system prompt and a hard cap on agent-loop iterations — but it's a real trade-off of using free-tier models over something like Claude or GPT-4 class models.
- **Free-tier model availability on OpenRouter changes frequently** — models get added, rate-limited, or removed from the free tier with little notice. If a model stops working, check [openrouter.ai/models](https://openrouter.ai/models) with the "Free" filter for current options, or use the `openrouter/free` auto-router.

## Limitations / possible extensions

- No persistent memory across separate program runs (only within a single chat session)
- No confirmation/permission prompts before running potentially destructive `Bash` commands
- Single file (`Main.java`) — could be split into separate classes for tools, execution, and the agent loop
- No streaming output (responses are printed only once complete)

## Tech stack

- Java 17
- [OpenAI Java SDK](https://github.com/openai/openai-java) (used against OpenRouter's OpenAI-compatible API)
- Maven