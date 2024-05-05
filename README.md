# cognitect-http-client

This is a fork of com.cognitect/http-client that is updated to work with Jetty
11, and will therefore coexist peacefully with ring/ring-jetty-adapter >=
1.11.0. 

There isn't a public repo for com.cognitect/http-client; the source is [only
distributed via the jar](https://github.com/cognitect-labs/aws-api/issues/23),
so this is based on the code from [com.cognitect/http-client
v1.0.127](https://mvnrepository.com/artifact/com.cognitect/http-client/1.0.127).

There are currently no tests here. It is unknown if the original source has
tests, as they are not included in the jar.

It maintains the same API as com.cognitect/http-client, and only has the
following changes to work with Jetty 11 instead of Jetty 9:

- it depends on Jetty 11.0.20 instead of 9.4.53.v20231009
- it no longer suppresses Jetty's announcement in logging, since the mechanism
  it was using to do that under Jetty 9 no longer exists. You should be able to
  set the system property `org.eclipse.jetty.util.log.announce` to `false` to
  get the same behavior.

## Version coordinates 

```clojure
org.tcrawley/cognitect-http-client {:mvn/version "1.11.128"}
```

## License 

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

