---
tags:
  - security
  - privacy
  - dns
  - email
date: 2025-06-23
---
## TLDR

A three-layer email setup (custom domains + alias service + encrypted mailbox) that isolates spam, identifies leaks, and keeps you portable across providers.

## Disclaimer

This setup requires the Proton Unlimited plan (or equivalent). The free tier does not support custom domains, limits you to one Proton address, and does not include SimpleLogin Premium (needed for reverse aliasing, i.e. sending emails *from* an alias). This article is not sponsored; these are tools I use and pay for.

## The problem with one address for everything

Most people use one or two email addresses for everything: shopping, banking, social media, friends. When one address gets leaked, spam floods the whole inbox and there is no way to tell which service sold you out. You also cannot disable the leaked address without losing access to everything else tied to it.

## Three layers

The solution is to separate your email into three layers, each solving a different problem. Custom domains are used at every layer: meaningful domains (`gandalf.me`, `gandalf.pro`) for Proton mailboxes, and a separate, intentionally random domain (`potatoes.me`) for SimpleLogin aliases.

```
Websites / Services              Friends / Professional
        │                                  │
        ▼                                  │
┌──────────────────┐                       │
│  Alias Service   │                       │
│  (SimpleLogin)   │                       │
│  potatoes.me     │                       │
│  one alias per   │                       │
│  website         │                       │
└────────┬─────────┘                       │
         │ forwards to                     │
         ▼                                 ▼
┌─────────────────────────────────────────────┐
│  Encrypted Mailbox (Proton)                 │
│  hi@gandalf.me · shopping@gandalf.me        │
│  contact@gandalf.pro · research@gandalf.pro │
│  Never exposed to websites                  │
└─────────────────────────────────────────────┘
         │
    Custom domains:
      gandalf.me, gandalf.pro → Proton (up to 3 domains)
      potatoes.me             → SimpleLogin
```

### Layer 1: Custom domains (portability)

Custom domains (`gandalf.me`, `gandalf.pro`, `potatoes.me`) mean your email addresses belong to you, not to Gmail, Proton, or SimpleLogin. If you ever want to switch providers, you update DNS records and everything keeps working. No need to notify contacts or update hundreds of logins.

I use multiple domains on Proton to organize by context: `.me` for personal, `.pro` for professional. The SimpleLogin domain is separate and intentionally random-looking, since websites do not care how your email domain looks.

Other benefits:
- Clean addresses like `hi@gandalf.me` or `contact@gandalf.pro` instead of `gandalf.contact.2024@gmail.com`
- You own the namespace, so no address is "already taken"
- The same domain can serve your portfolio site

### Layer 2: Alias service (spam isolation)

An alias service like [SimpleLogin](https://simplelogin.io) sits between websites and your real mailbox. You create one alias per website and never give your actual address to any service.

Using a custom domain on SimpleLogin (`potatoes.me` instead of `@simplelogin.co`) is important: some websites block known SimpleLogin and alias service domains. A custom domain sidesteps this entirely since the website has no way to tell it is an alias.

The key is adding a random suffix to each alias. If Gandalf registers on `fireworks.xyz`, his alias might be `fireworks.42tu7@potatoes.me`. The random suffix matters: without it, anyone who learns one alias (`fireworks@potatoes.me`) can guess the others (`instagram@potatoes.me`, `facebook@potatoes.me`).

**When spam arrives**, the email headers show which alias forwarded it. Gandalf starts getting junk on `shopping@gandalf.me`. He checks the headers and sees it all came through `pipe-weed.8eerf@potatoes.me`. Now he knows exactly which service leaked his address. He disables that one alias in SimpleLogin, the spam stops, and nothing else is affected.

**When you switch providers**, you change the forwarding address in SimpleLogin rather than updating every website login individually.

### Layer 3: Encrypted mailbox (privacy)

[Proton](https://proton.me) is the actual mailbox, never exposed to websites. With custom domains attached to Proton (up to 3 domains, up to 15 addresses on Unlimited), you get clean addresses for direct communication:

- `hi@gandalf.me` for friends (Gandalf trusts Galadriel not to leak it)
- `shopping@gandalf.me` as a forwarding target for shopping-related aliases
- `contact@gandalf.pro` for professional contacts and freelance inquiries
- `research@gandalf.pro` for academic and research correspondence

These addresses are private-facing. Only people you trust and SimpleLogin know they exist.

I use Proton specifically because I'd rather pay for an encrypted service than pay Google with my data. The Unlimited plan bundles SimpleLogin Premium, VPN, and Drive, which makes the cost easier to justify.

### Organizing your Proton inbox

With multiple domains and addresses, inbox organization matters. I use Proton's folder structure to mirror the domain and address hierarchy, with Sieve filters to sort incoming mail automatically:

```
├── me/                     # gandalf.me domain
│   ├── hi/                 # hi@gandalf.me
│   └── shopping/           # shopping@gandalf.me
└── pro/                    # gandalf.pro domain
    ├── contact/            # contact@gandalf.pro
    └── research/           # research@gandalf.pro
```

A Sieve filter script routes emails to the right folder based on the `X-Original-To` header:

```sieve
require ["fileinto", "imap4flags"];

if header :matches "X-Original-To" "hi@gandalf.me" {
  fileinto "me/hi";
} elsif header :matches "X-Original-To" "shopping@gandalf.me" {
  fileinto "me/shopping";
} elsif header :matches "X-Original-To" "contact@gandalf.pro" {
  fileinto "pro/contact";
} elsif header :matches "X-Original-To" "research@gandalf.pro" {
  fileinto "pro/research";
}
```

This keeps everything categorized without manual drag-and-drop.

## When to skip the alias

Not everything should go through SimpleLogin. Give your Proton address directly to:

- **Friends and family**: `hi@gandalf.me` is more personal than `friends.234rt@potatoes.me`
- **Professional contacts**: `contact@gandalf.pro` looks credible on a business card
- **Government services**: some institutions are picky about alias domains

The rule of thumb: if you need to email someone first (not just receive), or if the relationship is high-trust, use the Proton address directly.

## Recap

![Email flow diagram](/assets/media/email-privacy/example-diagram.png)

Gandalf uses `contact@gandalf.pro` on his website for a professional look. He uses `hi@gandalf.me` for close friends. For everything else, he creates a SimpleLogin alias on `potatoes.me` per service, forwarded to the appropriate Proton address. No website ever sees his real mailbox.

## Conclusion

This setup has three properties I care about:

- **Leak isolation**: one alias per website means spam is traceable and containable
- **Provider independence**: custom domains on both Proton and SimpleLogin mean I can switch either service by updating DNS records
- **Clean separation**: private addresses for people I trust, disposable aliases for everything else

It comes at a cost (domain registrations + Proton Unlimited), but the bundled services (VPN, Drive, SimpleLogin Premium) make it worthwhile.