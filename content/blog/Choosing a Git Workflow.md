---
tags:
  - git
  - devops
date: 2024-05-12
rss-feeds:
  - all
---

## TLDR

What Git workflow is suitable for your project: trunk-based, feature branching, forking, release branching, release candidate workflow, Feature branching to develop, GitFlow


## Introduction

Depending on the size of the projects and its CI/CD requirements, one might choose one of the popular [Git Workflows](https://www.atlassian.com/git/tutorials/comparing-workflows). Some are good for some scenarios, some are never good and some are questionable to say the least.

In this article, I will explain how the main workflows work and which one to use and when in my opinion.

## Trunk-Based Development

### Timeline Example

![Trunked Based Dev](/assets/media/git-workflows/trunk-based-dev.png)

### No Branching

That’s it. You have your `main` branch and everybody pushes to it. Some might call it madness others would say that excellent CI/CD setup does not require branching.

If you are the only one working on your project, you *could* push to `main` directly. If you are an excellent developer and have the confidence to push to main and have very good CI/CD in place or none (so merging to `main` is not critical), you could use that strategy. I see this strategy quite often in small open-source projects maintained by a single developer with manual release (so no CD, just CI for testing).

### Should you use it?

You might have realized already that this strategy applies to very few teams and I don’t think you will encounter that one-branch strategy a lot at your daily jobs. I don’t recommend that strategy as in my humble opinion, PRs are essentials in a good development process. Some people tend to view PR as someone having authority on their code but that’s the wrong way of seeing it. PR offers a second opinion on the code and **everybody** can suggest good changes. On my teams, everyone reviews everyone’s code regardless of seniority, and newer developers regularly surface good suggestions in PR comments.

Back to `TBD`, you need good trust in your colleagues as there is no code review. That is the reason I mentioned that it might be suitable for experience developers.

Anyway, don’t use trunk-based dev unless you know exactly what you are doing and have lots of experience already or a pretty non-critical project and you want very **fast** code base updates.

## Feature Branches

### Timeline Example

![Feature Branching](/assets/media/git-workflows/feature-branching.png)

### Pull Requests

Everybody should be familiar with that one. Bob pulls a branch from main, implements the feature and pushes that feature branch to remote. Bob then opens a PR/MR (Github call it Pull Request, Gitlab call it Merge Request) and Alice reviews Bob's code before merging to `main`.

If Alice suggests some changes, Bob pushes new commits to his `feature` branch. Once Alice approves the changes, Bob can merge to `main`.

### Solo Dev

I think that even for personal projects, you should create PR to merge into `main`. This allows you to define properly what is the `scope` of the changes you are working on. Furthermore,  you might have CI that checks format, run tests etc that would be different depending on pushing to a `feature` branch and merging to `main`.

For example, I have a portfolio website (Single Page Application) that is hosted on Netlify. When I open a PR, Netlify builds a js bundle and shows me a preview of what the new version of the website will look like on Web and Mobile. This is very convenient. Once I merge to `main`, Netlify deploys the new js bundle to my domain. So my PR triggers test check and UI preview (CI) and merging to `main` deployed the js bundle to my domain (CD).

### Working with others

Having `features` branches that are merged to `main` is the bare minimum to have when working with other developers in my opinion. 

Therefore, I suggest for the feature you want to implement, create a branch from `main`, solve the issue and raise a PR to get your colleague’s feedback. In your CI, describes the jobs you want to run on commit to a feature branch and the jobs you want to run when the code is merged to `main`.

Your `main` branch should be protected, meaning, only reviewed code can be merged to it and  nobody can push directly to it (thus the CI jobs cannot be bypassed).

This workflow is suitable for simple project with one or a few contributors and with simple CI/CD.

Finally, the feature branches should be **short lived.** Some people refer to CI (Continuous Integration) strictly as a way of saying we merge quickly to main even if the feature is partially implemented as long as it works in production (or hidden behind a flag for instance).

### GitHub Flow

The feature branching is what they use at GitHub, they call it `GitHub Flow` but it is the same as `feature branching`. See by yourself form their doc:

> So, what is GitHub Flow?
> 
> - Anything in the `main` branch is deployable
> - To work on something new, create a descriptively named branch off of `main` (ie: `new-oauth2-scopes`)
> - Commit to that branch locally and regularly push your work to the same named branch on the server
> - When you need feedback or help, or you think the branch is ready for merging, open a pull request
> - After someone else has reviewed and signed off on the feature, you can merge it into main
> - Once it is merged and pushed to 'main', you can and *should* deploy immediately

### Should you use it?

Yes. Actually, pretty much everybody uses feature branches.

## Forking

### Timeline Example

![Forking](/assets/media/git-workflows/forking.png)

### Open Source Contributions

Forking is the method used for open-source project contributions. In short, you could **clone** the repo locally but you won’t be able to push any branches because the author won't allow you. Just imagine if anybody could freely push branches to your repo! So the trick is to **fork** (personal copy on a version control platform) to your own GitHub account. Then you clone that repository instead and from there. The original Github repo is called the `upstream` and your own copy of the Github repo is called the `origin`.

Then, once your feature implemented, you can push the code to `origin` (your fork) and then raise a PR to merge the feature `origin/my-feature` to the `upstream/main` branch. When the authors/maintainers of the upstream repo approve your PR and merge it to `upstream/main` , you can then “sync” (merge `upstream/main` to `origin/main`) and start working on another feature.

To link the forking to our previous strategies, you can see that you are basically doing **feature branching** again. 

Some open-source authors might push directly to their `main` branch while accepting PR from forks. In that specific scenario, we can see that authors are doing **Trunk-Based Development** while requiring external contributors to follow **feature branching**. Interesting, isn’t it?

## Release Branches

### Timeline Example

![Release Branching](/assets/media/git-workflows/release-branching.png)

### It’s getting ugly

Indeed, some projects might have multiple versions deployed and accessible by clients at the same time. The common example would be the need to still support old products or old API versions.

In the timeline chart above, you can see that it is getting a bit more verbose but not so difficult to grasp. We branch `release-1.0` from `main`. Bob starts working on features and merges them to `release-1.0`. At some point, the code is deemed ready to be deployed and therefore merged to `main`. Bob quickly move on to build features for the next release `release1.1`.

Unfortunately, a bug is discovered in production and needs urgent fixing. Alice merges some hotfix into `main`  to patch the issue. The production is now stable and a new version arises from the patch: `v1.0.1`. We then sync `release-1.0` with `main` so our version on `release-1.0` is also `v1.0.1`

While Alice was patching `production`, Bob already pushed some features to the new release branch. So, we need to merge the patches made by Alice to Bob’s new code and that is why we also need to sync `release-1.1` with `main`. After syncing, Bob can merge is new release as `1.1.1` to `main`.

If you got confused with the version numbers, I redirect you to [SemVer](https://semver.org/) but in short, a version is of format *Major.Minor.Patch*. **Major** used for incompatible codes (like 2 independent API versions). **Minor** is in our example the `release` and **Patch** is the `hotfix` from Alice. This way when Bob merged his branch `release-1.1`, he did include the hotfix of Alice making the new version in `main` not `1.1.0` but indeed `1.1.1`.

### Should you use it?

If you don’t need to support multiple releases at once, no, don’t use it. Ideally, you merge your features quite frequently and one release does not break the other ones. It is actually very often the case that we do not need to support old versions. So if you can, don’t use it.

## GitFlow

### Timeline Example

![GitFlow](/assets/media/git-workflows/gitflow.png)

### Fatality

To quote [Atlassian](https://www.atlassian.com/git/tutorials/comparing-workflows/gitflow-workflow):

> Gitflow is a legacy Git workflow that was originally a disruptive and novel strategy for managing Git branches. Gitflow has fallen in popularity in favor of [trunk-based workflows](https://www.atlassian.com/continuous-delivery/continuous-integration/trunk-based-development), which are now considered best practices for modern continuous software development and [DevOps](https://www.atlassian.com/devops/what-is-devops) practices. Gitflow also can be challenging to use with [CI/CD](https://www.atlassian.com/continuous-delivery).
> 

So GitFlow is obsolete and you will soon understand why.

It is similar to what we just saw with the **release branching** but now we have another branch called `develop`. So every feature is merged to `develop`. Once a version is ready we merge it to the corresponding `release` branch. On that release branch, some additional commits might be pushed before merging to `main`. On new version merged to `main`, we need to sync A LOT. You can see on the chart above all the potential merge conflicts represented by a sword. I hope this visual representation highlights the problem: too many potential merge conflicts.

### But why?

It is a good question, I am not sure. The idea of having a `develop` branch is very common in a lot of projects, but why combine it with `release` branches like that I am not sure to be frank. I don’t recommend to use GitFlow and it seems obsolete for a reason. In general we want the following:

- as few branches as possible
- short lived branches with small or partial but workable features to be deployed

I see `GitFlow` as the opposite of `Continuous Integration` (in the sense of merging frequently new features and having new deployable codes ready regularly). For fun, let’s have a look at what happens after a hotfix in prod:

- hotfix-1.0.1 ⚔️ main
- main ⚔️ release-1.0
- main ⚔️ release-1.1
- main ⚔️ develop
- develop ⚔️ feature

I have the feeling that implementing it would mean having a dedicated engineer to take care of the branching, a sort of *Git gardener*.

For legacy big projects, it might still be in use or necessary but I personally think it should be avoided.

## Feature branching on develop

### Timeline Example

![Feature Branching on Develop](/assets/media/git-workflows/feature-branching-on-develop.png)

### Develop branch

The GitFlow aspect that most people still use is the `develop` branch. All the feature branches are merged to `develop` instead of `main`. Once `develop` is deemed ready for release, it is merged to `main`.

This is useful for a few reasons:

- at any time, we know the commit of the stable release (code in prod) via the `main` branch
- at any time, we know what is the latest commit of the ongoing new version via the `develop` branch

This seems like the sweet spot for most cases and that is why it is popular.

Merging a `feature` to `develop` triggers a bunch of CI jobs (the usual, format check, test checks etc)

Merging `develop` to `main` triggers a bunch of CI jobs (build a docker image, push it to a container registry for instance)

### Should you use it?

Yes. It is simple yet efficient.

## Release Candidate workflow

### Timeline Example

![Release Candidate Workflow](/assets/media/git-workflows/RC-workflow.png)

It is very similar to **Feature Branching to Develop**. The only difference is that when `develop` is merged to `main` it creates a **Release Candidate** (RC) to be tested in a test/staging environment. If an issue in the test environment arises, a hotfix is done and we have a new RC (RC2 in this case). Once everything is ok in the test env, we have a stable release (we just tag a branch basically).

The advantage of this strategy is that `main` is the line of truth for both test and prod env. `main` contains the RC and stable versions which is great for reporting what went wrong in the test cluster and what is stable in prod.

This strategy works if `main` does not automatically deploy to production. It could deploy something non-critical, such as a docker image of the app to a container registry for instance.

### Tagging Example

- Bob has merged a few features to `develop` and deemed `develop` ready to be merged to `main`. It is a release candidate with version `v1.0.0-RC1`
- Alice approves Bob's changes and merges `develop` to `main`
- Alice deploys the app to **staging** and realizes one feature needs correction.
- Alice branches out of `main` and implement the RC fix and the code is merged to `main`. The new version is `v1.0.0-RC2`.
- Alice redeploys to **staging** and everything works as expected. Thus Alice bumps the version to stable: `v1.0.0`. She then deploys to **prod**.
- Unfortunately, in a very edge case, a feature fails in production and needs urgent fixing.
- Alice branches out of `main` and implements the *hotfix* and merges back to `main`. The version is now `v1.0.1`.
- All is well now and it's time to *sync* `develop` with `main`.

### Recap

- `feature` branches are merged to `develop`
- `develop` branch is merged to `main` as version *x.y.z-RCp*
- `RC-fixes` branches are merged to `main` as new RCs until test passes in test env. Version is *x.y.z-RC(p+1)*
- `hotfix` branches are merged to `main` if urgent bug in prod env and version is incremented like so: *x.y.z+1*
- `main` branch is merged to `develop` (Sync) and eventual conflicts with new features are resolved
- new `features` are implemented for the version *x.(y+1)+z*

### Should you use it?

If you need a test/staging environment to test changes, RC strategy is good for you. However, if you have only one env and your CD is not critical, prefer the **Feature branching to develop**

## Conclusion

Use **trunk-based** development if you are working alone on a project or with experienced developers you can trust.

Prefer **feature branching** for the PR dedicated CI/feedback from colleagues or yourself.

Having a **develop** branch between the `features` and `main` branches helps you follow the “Continuous Integration” philosophy in the sense of frequently merging short-lived feature branches to a development line of truth (even if a bit ahead/diverging from main, production line of truth).

Only use **release branching** if it is absolutely required because of older release maintenance constraints.

If you have a test/staging env that needs to go through integration testing before going to prod, the **Release Candidate workflow** is advisable.

I think people tend to refer to CI as the test jobs done on PRs from `feature` to `develop` and CD to refer to the build jobs happening on merge to `main`. Others refer to CI as the philosophy of merging short/partial (but working) features as quickly as possible. This can be applied in **Feature branching to develop** in my opinion.

Taking the time to have the simplest branching strategy possible for your project can really make the development experience a bliss for all developers of your team. People should focus on implementing quality features and not doing some botanic (lots of branches… anybody?).