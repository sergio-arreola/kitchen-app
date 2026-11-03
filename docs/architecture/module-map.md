# Module Map

```text
core:domain
  ↑
core:engine
  ↑
core:application
  ↑          ↑
data:database data:importers
       \      /
       app:shared-ui
        ↑         ↑
   app:android app:desktop

Developer tooling consumes the same domain/application/import modules through `tools:dev-cli`.
```

Dependency direction is inward toward domain concepts. Platform entry points are leaves, not foundations.
