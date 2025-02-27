package app.cash.sqldelight.dialects.postgresql.ide

import app.cash.sqldelight.dialect.api.ConnectionManager
import app.cash.sqldelight.dialect.api.ConnectionManager.ConnectionProperties
import com.intellij.openapi.project.Project
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.sql.Connection
import java.sql.DriverManager

internal class PostgresConnectionManager : ConnectionManager {
  override fun createNewConnectionProperties(
    project: Project,
    prefilledProperties: ConnectionProperties?,
  ): ConnectionProperties? {
    val dialog =
      if (prefilledProperties == null) PostgresConnectionDialog(project)
      else {
        val properties = adapter.fromJson(prefilledProperties.serializedProperties)!!
        PostgresConnectionDialog(
          project = project,
          connectionName = prefilledProperties.key,
          host = properties.host,
          port = properties.port,
          databaseName = properties.databaseName ?: "",
          username = properties.username ?: "",
          password = properties.password ?: "",
        )
      }
    if (!dialog.showAndGet()) return null
    return ConnectionProperties(
      key = dialog.connectionKey,
      serializedProperties = adapter.toJson(
        ConnectionSettings(
          host = dialog.host,
          port = dialog.port,
          databaseName = dialog.databaseName.ifEmpty { null },
          username = dialog.username.ifEmpty { null },
          password = dialog.password.ifEmpty { null },
        ),
      ),
    )
  }

  override fun getConnection(connectionProperties: ConnectionProperties): Connection {
    val settings = adapter.fromJson(connectionProperties.serializedProperties)!!

    var url = "jdbc:postgresql://${settings.host}:${settings.port}"
    if (settings.databaseName != null) {
      url += "/${settings.databaseName}"
    }

    val previousContextLoader = Thread.currentThread().contextClassLoader
    return try {
      // When it iterates the ServiceLoader we want to make sure its on the plugins classpath.
      Thread.currentThread().contextClassLoader = this::class.java.classLoader
      if (settings.username != null) {
        DriverManager.getConnection(url, settings.username, settings.password ?: "")
      } else {
        DriverManager.getConnection(url)
      }
    } finally {
      Thread.currentThread().contextClassLoader = previousContextLoader
    }
  }

  @JsonClass(generateAdapter = true)
  internal data class ConnectionSettings(
    val host: String,
    val port: String,
    val databaseName: String?,
    val username: String?,
    val password: String?,
  )

  companion object {
    private val adapter = Moshi.Builder().build()
      .adapter(ConnectionSettings::class.java)
  }
}
