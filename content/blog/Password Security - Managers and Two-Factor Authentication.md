---
tags:
  - security
  - privacy
date: 2025-06-27
rss-feeds:
  - all
---
## TLDR

My password and authentication setup: Bitwarden for generating and storing credentials, Ente Auth for 2FA codes, and printed recovery codes stored offline. My wife uses the same stack daily, which is a good sign that the setup is genuinely accessible.

## Why bother

Most people reuse the same two or three passwords everywhere. When one service gets breached, attackers try those credentials on every other site. A password manager eliminates this by generating a unique random password per account and remembering it for you.

The second layer is two-factor authentication (2FA): even if someone gets your password, they cannot log in without a code from your phone. Together, a password manager and 2FA cover the two most common attack vectors: credential reuse and stolen passwords.

I set this up for myself first, then for my wife. She picked it up quickly and now manages the full stack (Bitwarden + Ente Auth + Proton) daily without help. If anything, that confirmed the setup is practical for daily use, not just for people who enjoy configuring things.

## Bitwarden

I use [Bitwarden](https://bitwarden.com/) because it is open source, works on every platform, and has been around long enough that I trust its track record.

### How it works

Bitwarden stores your credentials in an encrypted **vault** protected by a single **master password**. This is the only password you need to remember. Everything else (website logins, credit card details, secure notes) lives in the vault behind that one key.

The vault supports different entry types for different kinds of credentials:

![Bitwarden entry types](/assets/media/password-security/types.png)

### Generating passwords

The real value is not just storing passwords but **generating** them. When I register for a new site, I generate a random password (30+ characters) directly in Bitwarden. I never come up with passwords myself, and no two accounts share the same one.

![Bitwarden Password Generator](/assets/media/password-security/password.png)

### The browser extension

Bitwarden's browser extension is what makes daily use frictionless. When you visit a site where you have a saved login, the extension icon shows a badge:

![Bitwarden extension icon](/assets/media/password-security/extension.png)

This badge is also a **phishing defense**. If you navigate to what looks like `reddit.com` but the extension shows no saved login, something is off. The URL might be `redit.com` or the more subtle `ɾeddit.com` (different "r" character). I always check the badge before entering credentials.

When registering for a new site, clicking `+` in the extension pre-fills the URL and lets you generate a password and save the login in one step:

![Bitwarden Login tab](/assets/media/password-security/new-login.png)

After that, returning to the site is just auto-fill. The "too much work" objection is backwards: once set up, logging in is faster than typing a password from memory.

### Protecting the master password

The master password is the single point of failure. If someone gets it, they get everything. Two rules:

1. **Make it strong and memorable.** A long passphrase works well. It is acceptable to write it on paper and store it somewhere safe at home. Never store it digitally.
2. **Enable 2FA on the vault itself.** This is the most critical account you have. Even if someone learns your master password, they still need the second factor.

## Ente Auth for 2FA

Two-factor authentication means logging in requires both your password (something you know) and a code from your phone (something you have). Even a stolen password is useless without the second factor.

I use [Ente Auth](https://ente.io/auth/) as my authenticator app. It is open source, end-to-end encrypted, cross-platform (I use Android, my wife uses iOS), and backs up your 2FA codes across devices. The encrypted backup matters: if you lose your phone, you can restore your codes on a new device without re-enrolling every account.

I chose Ente over Authy (closed source, Twilio-owned, and in 2024 an unsecured API endpoint leaked 33 million users' phone numbers) and Google Authenticator (barebone app, stores seeds to Google Drive by default, and if Google bans your account you lose all your 2FA seeds with it). SMS-based 2FA is the weakest option: attackers can hijack your phone number through SIM swapping and intercept the codes. An authenticator app is just as easy to set up (scan a QR code once) and the codes never leave your device, so there is nothing to intercept. If a website offers both SMS and authenticator app, always pick the app.

### Where to enable 2FA

A good rule of thumb: enable 2FA on any account where a breach would cause real damage. At minimum:

- **Password manager** (Bitwarden): the most critical, since it guards everything else
- **Email**: email is the recovery path for most accounts, so compromising it cascades
- **Banking and government services**
- **Cloud storage** if it contains sensitive files

Some services enforce their own 2FA systems instead of supporting standard authenticator apps. Steam uses Steam Guard, brokers like IBKR require approval through their own mobile app, and most banks have a proprietary app that can only be activated on one phone at a time for security reasons. These are all forms of 2FA, they just do not use your authenticator app. Do not be surprised if the setup differs from site to site.

### Recovery codes: the one mistake to avoid

When you enable 2FA on a site, it gives you **recovery codes**, one-time-use codes for when your phone is unavailable. The mistake I see people make is storing recovery codes inside the same Bitwarden vault as their passwords.

This defeats the entire point of 2FA. If someone accesses your vault, they get both the password and the recovery code. Your "two factors" collapse into one.

Where to store recovery codes instead:

- **Print them** and store them at home, labelled by service
- **A separate vault** dedicated only to recovery codes, with a different master password


## Emails and OAuth

For email setup (dedicated addresses per context, alias services, encrypted mailboxes), see [Email Privacy with Custom Domains and Aliases](https://www.loicb.dev/blog/email-privacy-with-custom-domains-and-aliases). That article covers how I use Proton + SimpleLogin + custom domains to isolate spam and stay provider-independent.

One related decision: I avoid "Sign in with Google/Facebook" (OAuth) wherever possible. It is convenient, but it ties your access to a third party. If your Google account gets locked or your Facebook gets banned, you lose access to everything linked to it. Email + password + 2FA keeps you in control. And if you later want to switch email providers, updating a login is straightforward.

## The full setup

Putting it together, my daily security stack:

| Layer | Tool | Purpose |
|-------|------|---------|
| Passwords | [Bitwarden](https://bitwarden.com/) | Generate and store unique passwords per account |
| 2FA codes | [Ente Auth](https://ente.io/auth/) | Time-based codes, encrypted backup across devices |
| Recovery codes | Printed, stored at home | Offline fallback if phone is lost |
| Email | Proton + SimpleLogin | Encrypted mailbox, one alias per service |

The first three are free. The email layer requires Proton Unlimited (which bundles SimpleLogin Premium). This article covers the first three; the email layer is covered in [Email Privacy with Custom Domains and Aliases](https://www.loicb.dev/blog/email-privacy-with-custom-domains-and-aliases).