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

package com.actionml.templates.cb

import cats.data.Validated
import cats.data.Validated.{Invalid, Valid}
import com.actionml.core.storage.Mongo
import com.actionml.core.template.{Dataset, Event}
import com.actionml.core.validate._
import com.mongodb.casbah.Imports._
import salat.global._
import salat.annotations._
import salat.dao._
import com.mongodb.casbah.MongoConnection
import org.joda.time.DateTime
//import org.json4s.{DefaultFormats, Formats, MappingException}

import scala.language.reflectiveCalls

/** DAO for the Contextual Bandit input data
  * There are 2 types of input events for the CB 1) usage events and 2) property change events. The usage events
  * are stored as a potentially very large time ordered collection, the property change events translate
  * into changes to mutable DB objects and so are always up-to-date with the last change made. Another way to
  * look at this is that usage events accumulate until train creates an updateable model then them may be discarded
  * since the model acts as a watermark requiring no history to create predictions. The properties are attached to
  * objects and ony the most recent update is needed so although encoded as events they cause a property change in
  * real time to the object.
  *
  * See the discussion of Kappa Learning here: https://github.com/actionml/pio-kappa/blob/master/kappa-learning.md
  *
  * This dataset contains collections of users, usage events, and groups. Each get input from the datasets POST
  * endpoint and are parsed and validated but go to different collections, each with different properties. The
  * users and groups are mutable in Mongo, the events are kept as a stream that is truncated after the model is
  * updated so not unnecessary old data is stored.
  *
  * @param resourceId REST resource-id from POST /datasets/<resource-id> also ids the mongo table for all input
  */
class CBDataset(resourceId: String = "test_resource") extends Dataset[CBEvent](resourceId) with JsonParser with Mongo {

  val users = connection(resourceId)("users")
  var usageEventGroups: Map[String, UsageEventDAO] = Map.empty
  // val groups = store.connection(resourceId)("groups") // replaced with GroupsDAO
  // may need to createIndex if _id: String messes things up
  object GroupsDAO extends SalatDAO[GroupParams, String](collection = connection(resourceId)("groups"))


  // These should only be called from trusted source like the CLI!
  def init(json: String = ""): Validated[ValidateError, Boolean] = {
    Valid(true)
  }

  override def destroy(dbName: String) = {
    super.destroy(dbName)
  }

  // add one json, possibly an CBEvent, to the beginning of the dataset
  def input(json: String): Validated[ValidateError, CBEvent] = {
    parseAndValidateInput(json).andThen(persist)
  }


  def persist(event: CBEvent): Validated[ValidateError, CBEvent] = {
    try {
      event match {
        case event: CBUsageEvent => // usage data, kept as a stream
          if (usageEventGroups.keySet.contains(event.properties.testGroupId)) {
            logger.debug(s"Dataset: $resourceId persisting a usage event: $event")
            // input to usageEvents collection
            // Todo: validate fields first
            val eventObj = MongoDBObject(
              "userId" -> event.entityId,
              "eventName" -> event.event,
              "itemId" -> event.targetEntityId,
              "groupId" -> event.properties.testGroupId,
              "converted" -> event.properties.converted,
              "eventTime" -> new DateTime(event.eventTime)) //sort by this
            usageEventGroups(event.properties.testGroupId).insert(event.toUsageEvent)
            Valid(event)
          } else {
            logger.warn(s"Data sent for non-existent group: ${event.properties.testGroupId} will be ignored")
            Invalid(EventOutOfSequence(s"Data sent for non-existent group: ${event.properties.testGroupId}" +
              s" will be ignored"))
          }


        case event: CBUserUpdateEvent => // user profile update, modifies use object
          logger.debug(s"Dataset: $resourceId persisting a User Profile Update Event: $event")
          // input to usageEvents collection
          // Todo: validate fields first
          if (event.properties.nonEmpty) {
            val query = MongoDBObject("userId" -> event.entityId)
            // replace the old document with the 'apple' instance

            val builder = MongoDBObject.newBuilder
            builder += "userId" -> event.entityId
            builder ++= event.properties.get
            builder += "eventTime" -> new DateTime(event.eventTime)
            val eventObj = builder.result
            users.update(query, eventObj, true, false) // upsert true
            Valid(event)

          } else {
            // no properties so ignore
            logger.warn(s"Dataset: ${resourceId} got an empty User Profile Update Event: ${event} and " +
              s"will be ignored")
            Invalid(ParseError(s"Got an empty User Profile $$set event, ignored."))
          }

        case event: CBGroupInitEvent => // user profile update, modifies use object
          logger.trace(s"Persisting a Group Init Event: ${event}")
          // create the events collection for the group
          if (usageEventGroups.keySet.contains(event.entityId)) {
          // re-initializing
            usageEventGroups(event.entityId).collection.drop()
          }
          usageEventGroups = usageEventGroups +
            (event.entityId -> UsageEventDAO(connection(resourceId)(event.entityId)))

/*        val query = MongoDBObject("groupId" -> event.entityId)

          val builder = MongoDBObject.newBuilder
          builder += "groupId" -> event.entityId
          builder += "start" -> new DateTime(event.properties.testPeriodStart)
          if (event.properties.testPeriodEnd.nonEmpty) builder += "end" ->
            new DateTime(event.properties.testPeriodEnd.get)
          builder += "eventTime" -> new DateTime(event.eventTime)
          groups.findAndModify(query, builder.result())
*/
          //val groupParams = GroupParams(event.entityId, new DateTime(event.properties.testPeriodStart), event.properties.pageVariants, Some(new DateTime(event.properties.testPeriodEnd.getOrElse(None))))
          GroupsDAO.insert(event.toGroupParams)
          Valid(event)

        case event: CBUserUnsetEvent => // unset a property in a user profile
          logger.trace(s"Dataset: ${resourceId} persisting a User Profile Unset Event: ${event}")
          val update = new MongoDBObject
          val unsetPropNames = event.properties.get.keys.toArray

          users.update(MongoDBObject("userId" -> event.entityId), $unset(unsetPropNames: _*), true)
          Valid(event)

        case event: CBDeleteEvent => // remove an object, Todo: for a group, will trigger model removal in the Engine
          event.entityType match {
            case "user" =>
              logger.trace(s"Dataset: ${resourceId} persisting a User Delete Event: ${event}")
              users.findAndRemove(MongoDBObject("userId" -> event.entityId))
              Valid(event)
            case "group" | "testGroup" =>
              if ( usageEventGroups.keySet.contains(event.entityId) ) {
                GroupsDAO.remove(MongoDBObject("groupId" -> event.entityId))
                //groups.findAndRemove(MongoDBObject("groupId" -> event.entityId))
                usageEventGroups(event.entityId).collection.drop() // drop all events
                usageEventGroups = usageEventGroups - event.entityId // remove from our collection or collections
                logger.trace(s"Deleting group ${event.entityId}.")
                Valid(event)
              } else {
                logger.warn(s"Deleting non-existent group may be an error, operation ignored.")
                Invalid(ParseError(s"Deleting non-existent group may be an error, operation ignored."))
              }
            case _ =>
              logger.warn(s"Unrecognized $$delete entityType event: ${event} will be ignored")
              Invalid(ParseError(s"Unrecognized event: ${event} will be ignored"))
          }
        case _ =>
          logger.warn(s"Unrecognized event: ${event} will be ignored")
          Invalid(ParseError(s"Unrecognized event: ${event} will be ignored"))
      }
    } catch {
      case e @ (_ : IllegalArgumentException | _ : ArithmeticException ) =>
        logger.error(s"ISO 8601 Datetime parsing error ignoring input: ${event}", e)
        Invalid(ParseError(s"ISO 8601 Datetime parsing error ignoring input: ${event}"))
      case e: Exception =>
        logger.error(s"Unknown Exception: Beware! trying to recover by ignoring input: ${event}", e)
        Invalid(ParseError(s"Unknown Exception: Beware! trying to recover by ignoring input: ${event}, ${e.getMessage}"))
    }
  }

  def parseAndValidateInput(json: String): Validated[ValidateError, CBEvent] = {

    parseAndValidate[CBRawEvent](json).andThen { event =>
      event.event match {
        case "$set" => // either group or user updates
          event.entityType match {
            case "user" => // got a user profile update event
              parseAndValidate[CBUserUpdateEvent](json).andThen { cbue =>
                if (cbue.properties.isDefined != true) {
                  logger.trace(s"No properties for the user update event: ${event.event}")
                  Invalid(MissingParams(s"No properties for the user update event: ${event.event}"))
                } else Valid(cbue)
              }
            case "group" | "testGroup" => // got a group initialize event, uses either new or old name
              logger.trace(s"Dataset: ${resourceId} parsing a group init event: ${event.event}")
              parseAndValidate[CBGroupInitEvent](json)
          }

        case "$unset" => // remove properties
          event.entityType match {
            case "user" => // got a user profile update event
              logger.trace(s"Dataset: ${resourceId} parsing a user unset event: ${event.event}")
              parseAndValidate[CBUserUnsetEvent](json).andThen { uue =>
                if (uue.properties.isDefined) {
                  Invalid(MissingParams("No parameters specified, event ignored"))
                } else {
                  Valid(uue)
                }
              }
            case "group" | "testGroup" => // got a group initialize event, uses either new or old name
              logger.warn(s"Dataset: ${resourceId} parsed a group $$unset event: ${event.event} this is undefined " +
                s"and ignored.")
              Invalid(WrongParams("Group $unset is not allowed and ignored."))
          }

        case "$delete" => // remove an object
          event.entityType match {
            case "user" | "group" | "testGroup" => // got a user profile update event
              logger.trace(s"Dataset: ${resourceId} parsing an $$delete event: ${event.event}")
              parseAndValidate[CBDeleteEvent](json)
          }

        case _ => // default is a self describing usage event, kept as a stream
          logger.trace(s"Dataset: ${resourceId} parsing a usage event: ${event.event}")
          parseAndValidate[CBUsageEvent](json)
      }
    }
  }

}

//case class CBGroupInitProperties( p: Map[String, Seq[String]])

/* CBEvent partially parsed from the Json:
{
  "event" : "$set", //"$unset means to remove some properties (not values) from the object
  "entityType" : "user"
  "entityId" : "amerritt",
  "properties" : {
    "gender": ["male"],
    "country" : ["Canada"],
    "otherContextFeatures": ["A", "B"]
  }
  "eventTime" : "2014-11-02T09:39:45.618-08:00",
  "creationTime" : "2014-11-02T09:39:45.618-08:00", // ignored, only created by PIO
}
 */
case class CBUserUpdateEvent(
  entityId: String,
  // Todo:!!! this is the way they should be encoded, fix when we get good JSON
  properties: Option[Map[String, Seq[String]]] = None,
  //properties: Option[Map[String, String]],
  eventTime: String)
  extends CBEvent

case class CBUserUnsetEvent(
  entityId: String,
  // Todo:!!! this is teh way they should be encoded, fix when we get good JSON
  // properties: Option[Map[String, List[String]]],
  properties: Option[Map[String, Any]] = None,
  eventTime: String)
  extends CBEvent

/* CBUsageEvent string
Some values are ignored since the only "usage event for teh Contextual Bandit is a page-view.
{
  "event" : "page-view-conversion", // value ignored
  "entityType" : "user", // value ignored
  "entityId" : "amerritt",
  "targetEntityType" : "item", // value ignored
  "targetEntityId" : "item-1",
  "properties" : {
    "testGroupId" : "group-1",
    "converted" : true
  }
  "eventTime" : "2014-11-02T09:39:45.618-08:00",
  "creationTime" : "2014-11-02T09:39:45.618-08:00", // ignored, only created by PIO
}
*/
case class CBUsageProperties(
  testGroupId: String,
  converted: Boolean)

case class CBUsageEvent(
  event: String,
  entityId: String,
  targetEntityId: String,
  properties: CBUsageProperties,
  eventTime: String)
  extends CBEvent {

  def toUsageEvent: UsageEvent = {
    UsageEvent(
      event = this.event,
      userId = this.entityId,
      itemId = this.targetEntityId,
      testGroupId = this.properties.testGroupId,
      converted = this.properties.converted,
      eventTime = new DateTime(this.eventTime)
    )
  }
}

case class UsageEvent(
  _id: ObjectId = new ObjectId(),
  event: String,
  userId: String,
  itemId: String,
  testGroupId: String,
  converted: Boolean,
  eventTime: DateTime)

case class UsageEventDAO(eventColl: MongoCollection) extends SalatDAO[UsageEvent, ObjectId](eventColl)

/* CBGroupInitEvent
{
  "event" : "$set",
  "entityType" : "group"
  "entityId" : "group-1",
  "properties" : {
    "testPeriodStart": "2016-01-02T09:39:45.618-08:00",
    "testPeriodEnd": "2016-02-02T09:39:45.618-08:00",
    "items" : ["item-1", "item-2","item-3", "item-4", "item-5"]
  },
  "eventTime" : "2016-01-02T09:39:45.618-08:00" //Optional
}
*/
case class CBGroupInitProperties (
  testPeriodStart: String, // ISO8601 date
  pageVariants: Seq[String], //["17","18"]
  testPeriodEnd: Option[String])

case class GroupParams (
  _id: String,
  testPeriodStart: DateTime, // ISO8601 date
  pageVariants: Seq[String], //["17","18"]
  testPeriodEnd: Option[DateTime])


case class CBGroupInitEvent (
    entityType: String,
    entityId: String,
    properties: CBGroupInitProperties,
    eventTime: String) // ISO8601 date
  extends CBEvent {

  def toGroupParams: GroupParams = {
    GroupParams(
      _id = this.entityId,
      testPeriodStart = new DateTime(this.properties.testPeriodStart),
      pageVariants = this.properties.pageVariants,
      testPeriodEnd = if (this.properties.testPeriodEnd.isEmpty) None else Some(new DateTime(this.properties.testPeriodEnd.get))
    )
  }
}


/* CBUser Comes in CBEvent partially parsed from the Json:
{
  "event" : "$delete", // removes user: ammerrit
  "entityType" : "user"
  "entityId" : "amerritt",
  "eventTime" : "2014-11-02T09:39:45.618-08:00",
  "creationTime" : "2014-11-02T09:39:45.618-08:00", // ignored, only created by PIO
}
 */
case class CBDeleteEvent(
  entityId: String,
  entityType: String,
  eventTime: String)
  extends CBEvent

// allows us to look at what kind of specialized event to create
case class CBRawEvent (
    eventId: String,
    event: String,
    entityType: String,
    entityId: String,
    targetEntityId: Option[String] = None,
    properties: Option[Map[String, Any]] = None,
    eventTime: String, // ISO8601 date
    creationTime: String) // ISO8601 date
  extends CBEvent

trait CBEvent extends Event