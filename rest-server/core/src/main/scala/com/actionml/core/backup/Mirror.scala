/*
 * Copyright ActionML, LLC under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * ActionML licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.actionml.core.backup

import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneId}

import com.actionml.core.template.Engine
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging

/**
  * Basic trait for JSON back up. Every json sent to POST /engines/engine-id/events will be mirrored by
  * a trait or object extending this.
  *
  */
trait Mirroring extends LazyLogging {

  // globally set in server config
  lazy val config = ConfigFactory.load()

  // Put base dir of ALL mirroring in server/src/main/resources/reference.conf or env, like logs all will go here
  val mirrorContainer: String = if (config.getString("mirror.container").isEmpty) "" else config.getString("mirror.container")
  val mirrorType: String = if (config.getString("mirror.type").isEmpty) "" else config.getString("mirror.type")

  def mirrorEvent(engineId: String, json: String): Boolean
  def importEvents(engine: Engine, location: String): Boolean

  /**
    * Collection names are formatted with "yy-MM-dd" template. In a filesystems this is the file name
    * for mirrored files of events. It means differnent things so other types of Mirrors
    *
    * @return timestamp-based name
    */
  protected def batchName: String =
    // yearMonthDay is lexicographically sortable and one file per day seem like a good default.
    DateTimeFormatter.ofPattern("yy-MM-dd").format(LocalDateTime.now(ZoneId.of("UTC")))

  /**
    * Semantics of a Directory name, base dir/engine ID for all the implementations. Override if file/directory
    * semantics do not apply
    *
    * @param engineId Engine ID
    * @return directory name
    */
  protected def containerName(engineId: String): String = s"$mirrorContainer/$engineId"

}

/** TODO: will be used when mirroring is config selectable */
object Mirroring {
  val localfs = "localfs"
  val hdfs = "hdfs"
}