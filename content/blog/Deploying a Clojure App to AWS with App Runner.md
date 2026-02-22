---
tags:
  - clojure
  - aws
  - devops
  - docker
date: 2026-02-17
repos:
  - [lasagna-pattern, "https://github.com/flybot-sg/lasagna-pattern"]
rss-feeds:
  - all
---
## TLDR

How I migrated our Clojure app deployment from EC2 + load balancers to AWS App Runner with S3-backed storage, cutting monthly costs from ~$50 to ~$15 and eliminating most operational overhead.

## Context

The [flybot.sg](https://www.flybot.sg) website is a full-stack Clojure app (Ring backend, ClojureScript + Replicant SPA) running as a single container. It lives in the [lasagna-pattern](https://github.com/flybot-sg/lasagna-pattern) monorepo under `examples/flybot-site/`.

The original deployment used EC2 with Docker, an ALB for SSL termination and redirects, and an NLB for static IP (needed for the apex domain A record). It worked, but the load balancers alone cost ~$36/month for a site that serves modest traffic. I also had to manage Docker updates on the EC2 instance, EBS snapshots for the embedded Datalevin database, and security group rules.

In February 2026, I migrated to AWS App Runner with S3-backed Datahike and S3 for image uploads. This article covers the decisions behind that migration.

## Why App Runner

I evaluated several compute options:

| Option | Pros | Cons |
|--------|------|------|
| **EC2 + ALB + NLB** (old) | Full control | $36+/month LBs, manual Docker, EBS management |
| **ECS Fargate** | Managed containers | Task definitions, ALB still needed |
| **EKS** | Full Kubernetes | Massive overkill, $72+/month control plane |
| **App Runner** | Zero-config, auto-scale, built-in HTTPS | Less networking flexibility |

App Runner won because:

- **No load balancer needed.** SSL termination, HTTP-to-HTTPS redirect, and custom domain support are built in. That immediately eliminates $36/month.
- **No instance management.** No SSH, no Docker daemon, no security patches. Push an image and it runs.
- **Automatic scaling.** Scales to zero-ish (paused instances at reduced cost) and up based on traffic.
- **Simple pricing.** ~$15/month for low-traffic workloads (0.5 vCPU, 1 GB RAM).

## Architecture

```
┌──────────────┐     tag push      ┌──────────────────┐
│ GitHub Repo  │──────────────────▶│ GitHub Actions    │
│ (monorepo)   │  flybot-site-v*   │ (OIDC → AWS)     │
└──────────────┘                   └────────┬─────────┘
                                            │
                                   bb deploy (jibbit)
                                            │
                                            ▼
                                   ┌────────────────┐
                                   │  ECR            │
                                   │  flybot-site    │
                                   └────────┬───────┘
                                            │
                                   auto-deploy on
                                   latest tag update
                                            │
                                            ▼
                                   ┌────────────────┐
                                   │  App Runner     │
                                   │  0.5 vCPU/1 GB  │
                                   └────────┬───────┘
                                            │
                         ┌──────────────────┼──────────────────┐
                         ▼                  ▼                  ▼
                ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
                │ S3 (data)    │   │ S3 (uploads)  │   │ Google OAuth │
                │ Datahike     │   │ public read   │   │              │
                └──────────────┘   └──────────────┘   └──────────────┘
```

### Apex Domain Routing

App Runner only supports custom domains via CNAME, but apex domains (`flybot.sg`) cannot use CNAMEs per DNS spec. Rather than paying for another ALB/NLB just for a static IP, I use [redirect.pizza](https://redirect.pizza) (free tier) to 301-redirect the apex to `www.flybot.sg`.

## Building Container Images with Jibbit

[Jibbit](https://github.com/atomisthq/jibbit) is a Clojure wrapper around Google's [Jib](https://github.com/GoogleContainerTools/jib). It builds OCI container images directly from the classpath,no Docker daemon needed. I maintain a [fork](https://github.com/skydread1/jibbit) that adds configurable entry-point support for `JAVA_OPTS` expansion at runtime.

The `jib.edn` config:

```clojure
{:aot          true
 :main         sg.flybot.flybot-site.server.system
 :base-image   {:image-name "eclipse-temurin:21-jre" :type :registry}
 :target-image {:image-name "$ECR_REPO"
                :type       :registry
                :tagger     {:fn jibbit.tagger/tag}
                :authorizer {:fn   jibbit.aws-ecr/ecr-auth
                             :args {:type :environment :region "ap-southeast-1"}}}
 :entry-point  jibbit/entry-point}
```

Key choices:

- **AOT compilation** for faster startup,App Runner health checks have timeouts, and Clojure startup without AOT can be too slow.
- **eclipse-temurin:21-jre** instead of the full JDK,smaller image, sufficient for an AOT-compiled uberjar.
- **`:tagger`** uses the git tag (e.g., `flybot-site-v0.2.4`) as the image tag, giving immutable versioned images.
- **Custom entry-point** wraps the command in `/bin/sh -c` so `${JAVA_OPTS}` expands at runtime (the default Jibbit entry-point does not).

## CI/CD Pipeline

Deployments are triggered by git tags. The monorepo's `bb tag` task reads the version from `resources/version.edn`, creates and pushes the tag:

```bash
bb tag examples/flybot-site
# Creates tag: flybot-site-v0.2.4 → GitHub Actions picks it up
```

The GitHub Actions workflow builds the ClojureScript frontend, builds the container image with Jibbit, pushes to ECR, and tags it as `latest`. App Runner is configured to auto-deploy when `latest` updates.

Authentication uses OIDC federation,no long-lived AWS credentials stored in GitHub. GitHub issues a JWT, AWS validates it against a pre-configured OIDC provider, and issues short-lived session credentials. The only GitHub secret is `AWS_ROLE_ARN`.

## What Changed from EC2

| Aspect | EC2 + ALB + NLB | App Runner |
|--------|-----------------|------------|
| **Cost** | ~$36/month (LBs) + EC2 + EBS | ~$15/month |
| **SSL** | ACM cert + ALB listener rules | Built-in |
| **Apex domain** | NLB for static IP ($18/month) | redirect.pizza (free) |
| **Scaling** | Manual | Automatic |
| **Docker** | Installed on EC2, manual updates | Not needed (Jibbit) |
| **Database** | Datalevin embedded on EBS | Datahike on S3 |
| **Deploys** | SSH to EC2, pull, restart | `bb tag` → auto-deploy |
| **CI auth** | Long-lived `AWS_ACCESS_KEY_ID` | OIDC (short-lived tokens) |

The database migration from Datalevin to Datahike was a prerequisite. Both are solid Datalog databases, but Datalevin uses LMDB-based file storage,on EC2, I backed this with EBS snapshots. App Runner's ephemeral containers have no persistent filesystem, so I needed a database with a remote storage backend. Datahike supports S3 via [datahike-s3](https://github.com/replikativ/datahike-s3), which fits naturally: the database state lives in an S3 bucket, and any container instance can read/write it.

## Conclusion

- **App Runner eliminates infrastructure overhead**,no load balancers, no EC2 instances, no Docker daemon.
- **Jibbit builds container images without Docker**, directly from the Clojure classpath.
- **One-command deploys**,`bb tag` triggers the full pipeline from git tag to running container.
- **OIDC** eliminates long-lived AWS credentials in CI.
- **redirect.pizza** replaces a $36/month load balancer setup for apex domain routing.
- Monthly costs dropped from ~$50+ to ~$15 with less operational work.