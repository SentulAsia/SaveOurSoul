# Firebase config (not committed)

Put each environment's `GoogleService-Info.plist`, downloaded from its own Firebase project, here:

```
ios/Firebase/Dev/GoogleService-Info.plist    → bundle id org.saveoursoul.ios.dev
ios/Firebase/QA/GoogleService-Info.plist     → bundle id org.saveoursoul.ios.qa
ios/Firebase/Prod/GoogleService-Info.plist   → bundle id org.saveoursoul.ios
```

A build phase copies the right one into the app for the selected scheme. If a file is missing,
that build runs without analytics. See docs/ANALYTICS.md.
