#### Unreleased
 - *TBD*

### Added
 - forcePathStyle option to use path-style S3 URLs. By default S3 client uses subdomains for buckets, which is not supported by some S3-compatible storages (e.g. S3MockClient)
 - transferAcceleration option to enable [S3 Transfer Acceleration](https://aws.amazon.com/ru/s3/transfer-acceleration/) [PR#22](https://github.com/burrunan/gradle-s3-build-cache/pull/22)
 - Added support for specifying AWS profile in DSL [PR#24](https://github.com/burrunan/gradle-s3-build-cache/pull/24)

#### Changed
 - Updated software.amazon.awssdk from 2.17.267 to 2.20.61
 - Add dependency on httpclient 4.5.14 to workaround certificate resolution bug (see [issue#23](https://github.com/burrunan/gradle-s3-build-cache/issues/23))
 - Updated build-time dependencies

Thanks to [@Shaftway](https://github.com/Shaftway), and [@guynaa](https://github.com/guynaa) for the contributions.

#### 1.5
 - 2022-10-05 - [4 commits](https://github.com/burrunan/gradle-s3-build-cache/compare/v1.4...v1.5)
#### Changed
 - Added AWS STS dependency for profile-based authentication

Thanks to https://github.com/run3wide for the contribution!

#### 1.4
 - 2022-09-07 - [12 commits](https://github.com/burrunan/gradle-s3-build-cache/compare/v1.3...v1.4)
#### Changed
 - Updated AWS SDK to 2.17.267
 - Added software.amazon.awssdk:sso dependency to support AWS SSO login [PR#18](https://github.com/burrunan/gradle-s3-build-cache/pull/18)
 - Removed references of jcenter repository [PR#15](https://github.com/burrunan/gradle-s3-build-cache/pull/15)

Thanks to https://github.com/devdavidkarlsson, and https://github.com/francescocervone for contributions.

#### 1.3
 - 2022-01-07 - [2 commits](https://github.com/burrunan/gradle-s3-build-cache/compare/v1.2...v1.3)
##### Changed
 - Migrate to AWS SDK 2.17.106: it shades Jackson, so less dependencies for s3-build-cache

#### 1.2
##### Added
 - Gradle configuration cache support
 - Migrate to AWS SDK 2.16.52

#### 1.1
 - 2020-07-24 - [4 commits](https://github.com/burrunan/gradle-s3-build-cache/compare/v1.0.0...v1.1)

##### Added
 - Improved cache statistics output, added configuration properties `showStatisticsWhenImpactExceeds`, `showStatisticsWhenSavingsExceeds`, `showStatisticsWhenWasteExceeds`, `showStatisticsWhenTransferExceeds` 

#### 1.0.0
 - 2020-07-24 - [26 commits](https://github.com/burrunan/gradle-s3-build-cache/compare/v0.10.0...v1.0.0) by [Vladimir Sitnikov](https://github.com/vlsi)
 - This plugin is a fork of [myniva/gradle-s3-build-cache](https://github.com/myniva/gradle-s3-build-cache).
 [Burrunan](https://en.wikipedia.org/wiki/Burrunan_dolphin) adds lots of performance, functional, and security features.

##### Added
 - [S3Mock](https://github.com/adobe/S3Mock) for integration test [(77d62e07)](https://github.com/burrunan/gradle-s3-build-cache/commit/77d62e07e556acd39e15f0123939430946df21bb)  
 - Print cache performance statistics at the end of the build [(9b5d66e)](https://github.com/burrunan/gradle-s3-build-cache/commit/9b5d66e3796888b20cf4757f72fc94f32430f1f5)
 - Add `maximumCachedObjectLength` to limit the cached entry size [(950bdbc1)](https://github.com/burrunan/gradle-s3-build-cache/commit/950bdbc1b6960ad9d64eb54d609aaeb3938ce126)
 - Security: use custom environment variables for credentials to avoid unexpected use of AWS keys. `S3_BUILD_CACHE_ACCESS_KEY_ID`, `S3_BUILD_CACHE_SECRET_KEY`, `S3_BUILD_CACHE_SESSION_TOKEN` [(4183489e)](https://github.com/burrunan/gradle-s3-build-cache/commit/4183489e82f70f7b117b84462e655e01caeee7c9)
 - Security: allow anonymous access to S3 buckets.
 - Security: cache lookup needs only `GetObject` permission (`myniva` plugin requires `ListObjects` as well)
 - Security: cache store needs only `PutObject` permission
 - Performance: eliminate `doesObjectExists` API call to make cache lookup faster [(11a5c690)](https://github.com/burrunan/gradle-s3-build-cache/commit/11a5c6901039a9a66d41514d72435f888570b4f9)
 - Performance: send cache entries from `File` when `File` is available (avoids `OutOfMemoryError` for big cache entries) [(455321bf)](https://github.com/burrunan/gradle-s3-build-cache/commit/455321bf5eb7261e2acb5e0720ca791ea0e75e0b)
 - Cached items contain metadata (elapsed duration, task name, Gradle version) which help to analyze the cache contents

##### Changed
 - Rename `path` parameter to `prefix` to allow prefixes without the trailing slash [(1c6d7106)](https://github.com/burrunan/gradle-s3-build-cache/commit/1c6d710689c8659e4a0ddfdb2dde51805650cf32)
 - Minimal supported (and tested) Gradle version is 4.1
 - Minimal supported (and tested) Java version is 1.8
 - Use JUnit5 for tests
 - Improve IDE autoconfiguration for plugin development: add `.editorconfig`, `.gitattributes`, `.gitignore`
 - CI: migrate Travis -> GitHub Actions
 - Release automation: Skipkit -> com.github.vlsi.stage-vote-release [(56d2359e)](https://github.com/burrunan/gradle-s3-build-cache/commit/56d2359eabc001208092fbd327831a648b14fe1a)
 - Rewrite plugin in Kotlin [(11a5c690)](https://github.com/burrunan/gradle-s3-build-cache/commit/11a5c6901039a9a66d41514d72435f888570b4f9)
 - Bump Gradle: 4.0 -> 6.5.1
 - Add Gradle wrapper validation CI [(dc6a692d)](https://github.com/burrunan/gradle-s3-build-cache/commit/dc6a692d8fbf29da6a6e842b0f8d9f3a45055925)

##### Migration from `myniva/gradle-s3-build-cache`

- Use `S3_BUILD_CACHE_ACCESS_KEY_ID`, `S3_BUILD_CACHE_SECRET_KEY`, `S3_BUILD_CACHE_SESSION_TOKEN` properties to pass the credentials.
 Note: by default `burrunan` does not lookup default AWS credentials, and it will default to annonymous access if credentials are missing.
- If you want the plugin to use default AWS credentials, then configure `lookupDefaultAwsCredentials=true` in the remote cache configuration
- If you use `path=folder`, then update it to `prefix=folder/` (note the trailing slash)
- If you do not use `path=...`, then you need to configure an empty prefix: `prefix=""` otherwise the plugin would use `cache/`

#### 0.10.0
 - 2020-03-26 - [12 commits](https://github.com/burrunan/gradle-s3-build-cache/compare/v0.9.0...v0.10.0) by [Basil Brunner](https://github.com/myniva) (10), [Egor Neliuba](https://github.com/egor-n) (1), [Marcin Erdmann](https://github.com/erdi) (1)
 - Upgrade Shipkit to latest version [(#32)](https://github.com/myniva/gradle-s3-build-cache/pull/32)
 - Pin dependencies to fixed versions [(#31)](https://github.com/myniva/gradle-s3-build-cache/pull/31)
 - Cover AwsS3BuildCacheService#load with unit tests [(#27)](https://github.com/myniva/gradle-s3-build-cache/pull/27)
 - Pin build env to Java 8 [(#25)](https://github.com/myniva/gradle-s3-build-cache/pull/25)
 - Don't use a dynamic version for com.amazonaws:aws-java-sdk-s3 [(#20)](https://github.com/myniva/gradle-s3-build-cache/issues/20)
 - Document how expiration of cache entries can be configured using S3 object lifecycle management. [(#19)](https://github.com/myniva/gradle-s3-build-cache/pull/19)
 - Add option to configure expiration time of cache objects [(#1)](https://github.com/myniva/gradle-s3-build-cache/issues/1)

#### 0.9.0
 - 2019-03-15 - [5 commits](https://github.com/burrunan/gradle-s3-build-cache/compare/v0.8.0...v0.9.0) by [Basil Brunner](https://github.com/myniva) (2), Jose María Rodriguez (2), [chema](https://github.com/durbon) (1)
 - Add  AWS session token to credentials [(#16)](https://github.com/myniva/gradle-s3-build-cache/pull/16)

#### 0.8.0
 - 2019-01-26 - [6 commits](https://github.com/burrunan/gradle-s3-build-cache/compare/v0.7.0...v0.8.0) by Michael J Bailey (5), [Basil Brunner](https://github.com/myniva) (1)
 - Issue #13 Add support for custom headers [(#14)](https://github.com/myniva/gradle-s3-build-cache/pull/14)
 - Add an option to set custom HTTP headers [(#13)](https://github.com/myniva/gradle-s3-build-cache/issues/13)

#### 0.7.0
 - 2018-09-14 - [7 commits](https://github.com/burrunan/gradle-s3-build-cache/compare/v0.6.0...v0.7.0) by 4 authors
 - Commits: [Cristian Garcia](https://github.com/CristianGM) (3), [Basil Brunner](https://github.com/myniva) (2), CristianGM (1), [Patrick Double](https://github.com/double16) (1)
 - Allow to override AWS Credentials [(#12)](https://github.com/myniva/gradle-s3-build-cache/pull/12)
 - Add example S3 bucket permissions. [(#11)](https://github.com/myniva/gradle-s3-build-cache/pull/11)

#### 0.6.0
 - 2018-03-29 - [4 commits](https://github.com/burrunan/gradle-s3-build-cache/compare/v0.5.1...v0.6.0) by [Basil Brunner](https://github.com/myniva) (2), [Patrick Double](https://github.com/double16) (2)
 - Bucket path [(#10)](https://github.com/myniva/gradle-s3-build-cache/pull/10)

#### 0.5.1
 - 2018-03-28 - [2 commits](https://github.com/burrunan/gradle-s3-build-cache/compare/v0.5.0...v0.5.1) by [Basil Brunner](https://github.com/myniva) (1), [Patrick Double](https://github.com/double16) (1)
 - Only describe endpoint if specified in the configuration. [(#9)](https://github.com/myniva/gradle-s3-build-cache/pull/9)

#### 0.5.0
 - 2018-02-03 - [3 commits](https://github.com/burrunan/gradle-s3-build-cache/compare/v0.4.1...v0.5.0) by [Basil Brunner](https://github.com/myniva)
 - Add support for alternative S3 endpoints [(#8)](https://github.com/myniva/gradle-s3-build-cache/issues/8)

**0.4.1 (2017-10-18)** - [1 commit](https://github.com/burrunan/gradle-s3-build-cache/compare/v0.4.0...v0.4.1) by [Basil Brunner](http://github.com/myniva)
 - No pull requests referenced in commit messages.

**0.4.0 (2017-10-18)** - [14 commits](https://github.com/burrunan/gradle-s3-build-cache/compare/v0.3.0...v0.4.0) by [Basil Brunner](http://github.com/myniva) (11), Kamal Wood (3)
 - Add support for automated releases [(#7)](https://github.com/myniva/gradle-s3-build-cache/pull/7)
 - myniva/gradle-s3-build-cache#5: Adds option to use reduced redundancy… [(#6)](https://github.com/myniva/gradle-s3-build-cache/pull/6)

