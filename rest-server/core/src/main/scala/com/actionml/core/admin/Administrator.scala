/*
 * Copyright ActionML, LLC under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
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

package com.actionml.core.admin

import cats.data.Validated
import com.actionml.core.storage.Store
import com.actionml.core.template.{EngineParams, Engine}
import com.actionml.core.validate.ValidateError
import com.typesafe.scalalogging.LazyLogging

/** Handles commands or Rest requests that are system-wide, not the concern of a single Engine */
abstract class Administrator() extends LazyLogging {

  // engine management
  def addEngine(json: String, resourceId: Option[String] = None): Validated[ValidateError, Boolean]
  def removeEngine(engineId: String): Validated[ValidateError, Boolean]
  def list(resourceType: String): Validated[ValidateError, Boolean]
  def parseAndValidateParams(params: String): Validated[ValidateError, EngineParams]

  // startup and shutdown
  def init() = this
  def start() = this
  def stop(): Unit

}