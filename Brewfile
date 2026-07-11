# macOS dev/runtime environment for ai-life. Declarative — `brew bundle` installs only what's
# missing (idempotent). Usually run via scripts/bootstrap-mac.sh, or directly:
#   brew bundle --file Brewfile

# --- CLI / build ---
brew "git"
brew "gh"
brew "maven"
brew "ollama"      # local inference engine — native on the host (Metal), NOT the container one

# --- Apps ---
cask "temurin@25"              # JDK 25 (Java 25 LTS — the build target)
cask "docker-desktop"          # container runtime for the compose stack
cask "claude-code"             # Claude Code CLI (stable channel)
cask "intellij-idea"           # Ultimate (per owner's choice)
cask "postman"
