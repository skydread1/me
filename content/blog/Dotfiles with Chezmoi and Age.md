---
tags:
  - dotfiles
  - security
  - macos
  - cli
  - git
date: 2026-05-12
rss-feeds:
  - all
---

## TLDR

My macOS environment lives in a single git repo managed by [chezmoi](https://www.chezmoi.io/), with secrets encrypted at rest using [age](https://github.com/FiloSottile/age). Bootstrapping a new machine is one script and one manual step. I started with the Bitwarden CLI driving chezmoi's secret templates, then migrated to age. The April 2026 Bitwarden CLI supply chain attack on npm landed after that migration.

## Why a dotfiles repo

A dotfiles repo gives me a single source of truth for my shell, git, editor, AWS, and SSH config across machines. The simplest version is a folder with symlinks:

```bash
ln -sf ~/dotfiles/.zshrc ~/.zshrc
```

That works until you need secrets in the repo. At that point you either keep secrets out of git (and rebuild them by hand on every new machine), or you pick a tool that encrypts them in place.

The popular options:

| Tool             | Approach              | Secrets                    | Complexity |
|------------------|-----------------------|----------------------------|------------|
| GNU Stow         | Symlinks only         | None (manage yourself)     | Very low   |
| Bare git repo    | `git --bare` in `$HOME` | None                     | Minimal    |
| yadm             | Git-based             | GPG or OpenSSL (plus transcrypt, git-crypt) | Low |
| chezmoi          | Declarative + templated | age, GPG, vault integrations | Medium |
| Nix Home Manager | Declarative           | sops-nix, agenix           | High       |

I picked chezmoi for two reasons: it is declarative (the repo is the source, `chezmoi apply` writes to `$HOME`), and it has per-file `age` encryption built in. Nix Home Manager solves the same problem more thoroughly, but it brings a whole package-management worldview with it. chezmoi was the sweet spot for me: templating when I need it, age built in, nothing else to install.

## Layout

```
dotfiles/                               (git repo)
├── Brewfile                            Homebrew packages manifest
├── bin/                                Bootstrap and backup/restore scripts
├── dot_zshrc                           Deployed as ~/.zshrc
├── dot_gitconfig                       Deployed as ~/.gitconfig
├── dot_aws/
│   ├── config                          Plain AWS config
│   └── encrypted_credentials.age       Encrypted AWS keys
├── encrypted_dot_secrets.sh.age        Encrypted env-var secrets
└── private_dot_ssh/                    SSH keys, age-encrypted, 0600 perms
```

The naming is chezmoi's convention. Filename prefixes drive behaviour at apply time:

- `dot_` becomes a leading dot in the destination (`dot_zshrc` → `~/.zshrc`)
- `private_` removes group and world permissions on apply (so files land as `0600`)
- `encrypted_` triggers age decryption on apply
- `executable_` marks the file executable on apply

The chezmoi source directory is `~/.local/share/chezmoi`, symlinked to the git checkout. The age private key lives at `~/.config/chezmoi/key.txt` and is never committed.

## How age encryption fits in

`age` is public-key encryption: one identity file holds your private key, recipients are public keys, and the CLI has a small set of subcommands (`age`, `age-keygen`).

Generating the key pair is a one-time step:

```bash
age-keygen -o ~/.config/chezmoi/key.txt
# Public key: age1...
```

The public key (recipient) goes into `~/.config/chezmoi/chezmoi.toml`:

```toml
encryption = "age"

[age]
  identity = "~/.config/chezmoi/key.txt"
  recipient = "age1..."
```

After that, adding a secret file is one command:

```bash
chezmoi add --encrypt ~/.aws/credentials
```

chezmoi reads the file, encrypts it with the recipient public key, and stores it in the source directory as `dot_aws/encrypted_credentials.age`. The git repo only ever sees the encrypted blob.

## What I encrypt, and why

| Deployed to            | Source in repo                          | Contents                          |
|------------------------|------------------------------------------|-----------------------------------|
| `~/.aws/credentials`   | `dot_aws/encrypted_credentials.age`     | AWS access keys                   |
| `~/.secrets.sh`        | `encrypted_dot_secrets.sh.age`          | Git emails, private registry credentials |
| `~/.ssh/*`             | `private_dot_ssh/encrypted_*.age`       | SSH keys, PEM files, ssh config   |

The flow on a new machine, after restoring the age key:

1. Encrypted files sit in the repo as `.age` blobs
2. `chezmoi apply` reads them, decrypts with `~/.config/chezmoi/key.txt`, and writes the decrypted content to `~/`
3. `~/.zshrc` sources `~/.secrets.sh` for environment variables (git emails, registry credentials, etc.)
4. `~/.aws/credentials` and `~/.ssh/*` land with the right permissions automatically

There is no password prompt and no vault unlock. The age key sits on disk, protected the same way an SSH key is: file permissions plus FileVault.

## Why I left the Bitwarden CLI behind

My first version of this setup used the Bitwarden CLI as the secrets source for chezmoi.

chezmoi has first-class support for Bitwarden through the `bitwarden` and `bitwardenFields` template functions. Secrets never sit on disk encrypted; instead, every `chezmoi apply` shells out to `bw get` and pulls the value live from your vault. It is a clean model in theory, and a lot of people use it well.

I migrated off it for four reasons:

1. **Friction**. Every `chezmoi apply` required `bw unlock` and typing a master password in the terminal.
2. **Master password exposure**. Typing it in the shell leaks it to clipboard managers, process listings, and shell history.
3. **All-or-nothing**. Editing a non-secret file would still trigger every Bitwarden lookup in every template.
4. **Heavyweight**. The secrets I actually need are a handful of values that change rarely. Unlocking a full vault to read them on every apply is overkill.

age gave me a much smaller threat surface. The key is a file. If the laptop is encrypted at rest and the file is `0600`, it has the same security properties as `~/.ssh/id_ed25519`. If I lose the laptop, FileVault is the layer that matters. If I want a second factor I can passphrase-protect the age key, the same option `ssh-keygen` gives you.

Threat-model comparison:

| Threat                              | Bitwarden CLI         | age (FileVault on)         |
|-------------------------------------|------------------------|----------------------------|
| Master password leaked in terminal  | Yes                    | No password to leak        |
| Shell history / process list leak   | Risk                   | None                       |
| Stolen laptop (disk encrypted)      | Safe                   | Safe                       |
| Malicious script reading files      | Safe (vault locked)    | Same risk as SSH keys      |
| Supply chain compromise of CLI tool | Yes (see below)        | `age` is a small, audited binary |

### The April 2026 incident

On April 22, 2026, a malicious version `2026.4.0` of the `@bitwarden/cli` npm package was published. It was live on the registry for roughly 1.5 hours before being pulled. The payload exfiltrated npm tokens, GitHub authentication tokens, SSH keys, and cloud credentials for AWS, Azure, and Google Cloud, and used the stolen npm tokens to attempt to self-propagate into other packages the victim could publish to.

The root cause was not Bitwarden itself, it was a compromise of [Checkmarx](https://www.bleepingcomputer.com/news/security/bitwarden-cli-npm-package-compromised-to-steal-developer-credentials/)'s GitHub Actions, one of which Bitwarden used in its release pipeline. The attacker rode the supply chain through CI rather than through the Bitwarden codebase. Bitwarden re-released a clean version, `2026.4.1`, shortly after.

The blast radius of a tool that can read your entire secrets vault is exactly equal to your secrets vault. A 1.5-hour window on a single package registry is enough for that to matter.

I still use Bitwarden as a password manager. The age private key is stored in my vault like any other secret, and on a new machine I copy it to `~/.config/chezmoi/key.txt` by hand. That is the only time Bitwarden touches the dotfiles flow.

## SSH key management

SSH keys are age-encrypted in `private_dot_ssh/` and deployed with `chezmoi apply` like everything else. Two conventions worth calling out:

**Name keys by purpose, not by protocol or person.** `id_ed25519_signing` is fine because it describes what the key does. `work-bastion` is fine. `bob_key` is not, because the key outlives the colleague's role. Generic names like `id_rsa` collide on machines that have more than one identity.

**Let `~/.ssh/config` be the documentation.** Instead of keeping a separate inventory file, I document each key inline:

```
Host work-bastion
  HostName bastion.example.com
  User ec2-user
  IdentityFile ~/.ssh/work-bastion

Host work-gitlab
  PreferredAuthentications publickey
  IdentityFile ~/.ssh/work-gitlab
```

The benefit is twofold. First, `ssh work-bastion` does the right thing without me thinking about which key or which user. Second, the config file becomes the source of truth for which keys exist and why. No drift between an inventory note and the actual ssh config.

The hygiene rule: do not put IP addresses in filenames. They get committed, they end up in git history, and they tie a key's identity to an ephemeral network location.

The signing key (`id_ed25519_signing`) doubles as the commit-signing identity. `~/.gitconfig` is deployed by chezmoi and already wires it up:

```ini
[gpg]
  format = ssh
[user]
  signingkey = ~/.ssh/id_ed25519_signing.pub
[commit]
  gpgsign = true
[tag]
  gpgsign = true
```

So new commits sign themselves on a fresh machine the moment `chezmoi apply` finishes.

## The orchestrator scripts

chezmoi handles file deployment, but a real machine also needs Homebrew packages, VSCode settings, and a dock layout. Those live in `bin/` as small, single-purpose shell scripts:

| Script           | Purpose                                                       |
|------------------|---------------------------------------------------------------|
| `bootstrap.sh`   | First-time setup: Xcode CLI, Homebrew, chezmoi, age, apply    |
| `dot.sh`         | Orchestrator: `dot.sh backup [component]` / `restore [component]` |
| `brew.sh`        | Backup/restore Homebrew packages via `Brewfile`               |
| `vscode.sh`      | Back up VSCode settings and snippets into the chezmoi source  |
| `dock.sh`        | One-time dock layout setup via `dockutil`                     |

`bin/` is excluded from chezmoi state via `.chezmoiignore` along with `README.md` and `Brewfile`. The scripts manage the chezmoi source, they are not deployed by it.

The orchestrator pattern means I never remember the individual commands:

```bash
./bin/dot.sh backup        # back up VSCode + Brewfile + gitconfig
./bin/dot.sh restore       # install brew packages + set up dock
./bin/dot.sh backup brew   # one component at a time
```

## New machine setup

The full bootstrap is one script and one manual step:

1. Restore the age key from Bitwarden to `~/.config/chezmoi/key.txt`
2. Run `bin/bootstrap.sh`, which installs Xcode CLI, Homebrew, chezmoi, and age, then runs `chezmoi apply`
3. Run `bin/dot.sh restore` to install Brewfile packages and configure the dock
4. Upload the SSH signing pub key to GitHub and GitLab

Step 1 is the only thing I cannot automate, by design. The age key is what every other step ultimately depends on, so it has to come from somewhere outside the repo itself.

The two pieces that have served me well: secrets at rest with age (no daemon, no vault, no master password prompt), and `~/.ssh/config` as the canonical SSH inventory. The rest is just letting file permissions and FileVault do the heavy lifting.