---
tags:
  - aws
  - devops
  - dns
date: 2023-11-09
rss-feeds:
  - all
---
## TLDR

The apex domain problem: `flybot.sg` cannot use a CNAME record, but most modern hosting services (App Runner, Vercel, Netlify) only provide DNS names, not static IPs. I went from a $36/month ALB+NLB setup to a free redirect service.

## The problem

I wanted `flybot.sg` to redirect to `www.flybot.sg`, where the app is hosted.

The subdomain is easy: a CNAME record pointing `www` to the hosting provider's DNS name. But the apex domain (`flybot.sg`, the "naked domain") cannot use a CNAME per DNS spec. It requires an A record, which needs a static IP.

This is a universal problem. It affects anyone using a service that exposes a DNS name rather than a static IP, which includes most modern container and serverless platforms.

## Solutions I evaluated

### GoDaddy forwarding

GoDaddy (my registrar) offers built-in domain forwarding. However, as of 2018, it does not forward paths:

- `flybot.sg` → `www.flybot.sg` (works)
- `flybot.sg/blog` → `www.flybot.sg/blog` (404)

It also lacks SSL for the apex domain, so `https://flybot.sg` fails entirely. Not viable.

### Route 53 with ALIAS records

AWS Route 53 supports a proprietary ALIAS record type that maps an apex domain to an ALB DNS name. This would solve the problem, but requires transferring the entire domain's DNS management to AWS. With GoDaddy, you cannot add NS records for the apex, only for subdomains, so you would need to change the default name servers, handing all DNS resolution to AWS.

This felt like too much vendor lock-in for a simple redirect.

### ALB + NLB ($36/month)

This is the setup I originally used. An internal ALB handled SSL termination (ACM certificates) and HTTP-to-HTTPS + apex-to-www redirects. An internet-facing NLB sat in front of the ALB, providing a static IP for the A record.

It worked, but the cost was disproportionate: two load balancers at ~$18/month each, just to redirect a handful of apex domain requests.

### Redirect services (free)

Dedicated redirect services like [redirect.pizza](https://redirect.pizza), [forwarddomain.net](https://forwarddomain.net), and others have emerged specifically for this problem. They provide:

- A static IP for your A record
- Automatic Let's Encrypt certificates for the apex domain
- 301 redirects with full path preservation
- Free tiers generous enough for most sites

## What I use now

I switched to [redirect.pizza](https://redirect.pizza) (free tier, up to 100k hits/month). The setup is:

1. Point `www` CNAME to the hosting provider (App Runner in my case)
2. Point `@` A record to redirect.pizza's static IP
3. Configure the redirect: `flybot.sg` → `https://www.flybot.sg` (301, HTTPS)

The service auto-provisions a Let's Encrypt certificate for the apex, so both `http://flybot.sg` and `https://flybot.sg` redirect correctly with full path forwarding.

## Conclusion

The apex domain redirect problem has existed for as long as CNAME restrictions have, but the solutions have gotten dramatically cheaper. Unless you need the ALB/NLB for other reasons (complex routing rules, WebSocket support, multiple target groups), a dedicated redirect service is the pragmatic choice. It replaced $36/month of AWS infrastructure with a free service that took five minutes to configure.

See also: [Deploying a Clojure App to AWS with App Runner](https://www.loicb.dev/blog/deploying-a-clojure-app-to-aws-with-app-runner-md) for the full migration from EC2+ALB+NLB to App Runner.