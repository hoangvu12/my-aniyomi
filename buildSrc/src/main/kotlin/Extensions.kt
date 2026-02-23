import org.gradle.api.Project

val Project.baseVersionCode: Int
    get() = project.findProperty("baseVersionCode")?.toString()?.toInt() ?: 1
