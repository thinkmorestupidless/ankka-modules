# Contract: the runtime check

`io.eitheror.casino.modules.Runtimes`, in the modules tool (`ankka casino`).

```scala
/** The runtimes of `named` that a platform at `platform` cannot run; every one when it is not reported. */
def unsupported(platform: Option[String], named: Set[String]): Set[String]
```

**The rule** is ankka's (`Compatibility.supports`): the runtime's major version equals the
platform's, and its minor is the platform's or the one below. Patch versions are not compared.

| Platform | Runtime | Verdict |
|---|---|---|
| 0.18.0 | 0.18.0, 0.18.3, 0.17.0 | supported |
| 0.18.0 | 0.16.3, 0.13.0, 0.19.0 | unsupported |
| 0.16.3 | 0.18.0 | unsupported |
| not reported | any | unsupported |

**Where it is called**: every apply, of a brand or of a shared project, whether or not the brand
takes real money, in local mode and before a push to the vendor, after validation and before
anything is deployed.

**The platform's version**: `GET /installation`'s `platformVersion` when the control plane is
asked; else `PLATFORM_VERSION` in `installation/overlays/<name>/settings.env`, which states what
that installation runs today (`dev-kind`: 0.16.3 until it is rebuilt). An installation's overlay is never set
to 0.18.0 to make an apply pass. The tool's own suites apply against a fixture root whose
`dev-kind` overlay states 0.18.0 (T029): a fixture's overlay is not an installation's.

**The refusal** (exit 1, nothing deployed):

```text
refused: the installation dev-kind runs platform 0.16.3, which cannot run runtime 0.18.0
  (named by: core/wallet, core/players, casino/game-provider, …)
  a platform runs runtimes of its own minor version and the one below
```

or `… reports no platform version (from <source>)`.

**Tests**: `RuntimesSuite` (the table above); the apply's suite gains one case each way.
`features/brands/platform-version.feature`'s fourth scenario is bound to it.
