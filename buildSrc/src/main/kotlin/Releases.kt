/*
 * Copyright 2023-2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

interface LibraryArtifact {
  /** Maven coordinate artifact id. */
  val artifactId: String

  /** Maven coordinate version. */
  val version: String

  /** Descriptive name for library. */
  val name: String
}

object Releases {
  const val groupId = "dev.ohs.fhir"

  // Libraries
  // After releasing a new version of a library, you will need to bump up the library version
  // in Dependencies.kt (in a separate PR)

  object DataCapture : LibraryArtifact {
    override val artifactId = "data-capture"
    override val version = "1.3.1"
    override val name = "Android FHIR Structured Data Capture Library"
  }

  object Contrib {
    object Barcode : LibraryArtifact {
      override val artifactId = "contrib-barcode"
      override val version = "0.1.0-beta3"
      override val name = "Android FHIR Structured Data Capture - Barcode Extensions (contrib)"
    }

    object LocationWidget : LibraryArtifact {
      override val artifactId = "contrib-locationwidget"
      override val version = "0.1.0-alpha01"
      override val name =
        "Android FHIR Structured Data Capture - Location Widget Extensions (contrib)"
    }
  }

  // Demo apps
  object Catalog {
    const val applicationId = "dev.ohs.fhir.catalog"
    const val versionCode = 1
    const val versionName = "1.0"
  }
}

fun Project.publishArtifact(artifact: LibraryArtifact) {
  val variantToPublish = "release"
  project.extensions
    .getByType<com.android.build.gradle.LibraryExtension>()
    .publishing
    .singleVariant(variantToPublish) { withSourcesJar() }
  afterEvaluate {
    configure<PublishingExtension> {
      publications {
        register<MavenPublication>(variantToPublish) {
          groupId = Releases.groupId
          artifactId = artifact.artifactId
          version = artifact.version
          from(components[variantToPublish])
          pom {
            name.set(artifact.name)
            licenses {
              license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
              }
            }
          }
          repositories {
            maven {
              name = "CI"
              url =
                if (System.getenv("REPOSITORY_URL") != null) {
                  // REPOSITORY_URL is defined in .github/workflows/build.yml
                  uri(System.getenv("REPOSITORY_URL"))
                } else {
                  uri("file://${rootProject.buildDir}/ci-repo")
                }
              version =
                if (project.providers.environmentVariable("GITHUB_ACTIONS").isPresent) {
                  // ARTIFACT_VERSION_SUFFIX is defined in .github/workflows/build.yml
                  "${artifact.version}-${System.getenv("ARTIFACT_VERSION_SUFFIX")}"
                } else {
                  artifact.version
                }
              if (System.getenv("GITHUB_TOKEN") != null) {
                credentials {
                  username = System.getenv("GITHUB_ACTOR")
                  password = System.getenv("GITHUB_TOKEN")
                }
              }
            }
          }
        }
      }
    }
  }
}
