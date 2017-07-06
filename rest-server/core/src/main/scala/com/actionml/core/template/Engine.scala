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

package com.actionml.core.template

import cats.data.Validated
import cats.data.Validated.Valid
import com.actionml.core.backup.FSMirroring
import com.actionml.core.validate.{JsonParser, ValidateError}
import com.typesafe.scalalogging.LazyLogging

/** Forms the Engine contract. Engines parse and validate input strings, probably JSON,
  * and sent the correct case class E extending Event of the extending
  * Engine. Queries work in a similar way. The Engine is a "Controller" in the MVC sense
  */
abstract class Engine extends LazyLogging with JsonParser with FSMirroring {

  // Todo: not sure how to require a val dataset: Dataset, which takes a type of Event parameter Dataset[CBEvent]
  // for instance. Because each Dataset may have a different parameter type
  var engineId: String = _
  var engineMirrorType: Option[String] = _
  var engineMirrorContainer: Option[String] = _

  def init(json: String): Validated[ValidateError, Boolean] = {
    parseAndValidate[RequiredEngineParams](json).andThen { p =>
      // todo: Slava these params are from the JSON engine config file and can be used to initialize the Mirror
      // they do nothing in the current code since these are set server wide in bin/harness-env
      // we want to control them per engine and so setup mirroring here with params from the engine JSON file
      engineMirrorType = p.mirrorType
      engineMirrorContainer = p.mirrorContainer
      Valid(true)
    }
  }

  def initAndGet(json: String): Engine
  def destroy(): Unit
  def start(): Engine = { logger.trace(s"Starting base Engine with engineId:$engineId"); this }
  def stop(): Unit = { logger.trace(s"Stopping base Engine with engineId:$engineId") }

  def train()
  def input(json: String, trainNow: Boolean = true): Validated[ValidateError, Unit] =
    mirrorEvent(engineId, json.replace("\n", " ") + "\n")
  Valid(())

  def query(json: String): Validated[ValidateError, String]
  def status(): String = "Does not support status message."
}

case class RequiredEngineParams(
  engineId: String, // required, resourceId for engine
  engineFactory: String,
  mirrorType: Option[String] = None,
  mirrorContainer: Option[String] = None) extends EngineParams

trait EngineParams
trait QueryResult
trait Query
