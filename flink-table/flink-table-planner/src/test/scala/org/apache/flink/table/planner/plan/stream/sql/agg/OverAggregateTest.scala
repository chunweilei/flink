/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.planner.plan.stream.sql.agg

import org.apache.flink.table.api._
import org.apache.flink.table.planner.plan.utils.FlinkRelOptUtil
import org.apache.flink.table.planner.runtime.utils.JavaUserDefinedAggFunctions.OverAgg0
import org.apache.flink.table.planner.utils.{TableTestBase, TableTestUtil}

import org.assertj.core.api.Assertions.{assertThat, assertThatExceptionOfType, assertThatThrownBy}
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class OverAggregateTest extends TableTestBase {

  private val util = streamTestUtil()
  util
    .addDataStream[(Int, String, Long)]("MyTable", 'a, 'b, 'c, 'proctime.proctime, 'rowtime.rowtime)

  def verifyPlanIdentical(sql1: String, sql2: String): Unit = {
    val table1 = util.tableEnv.sqlQuery(sql1)
    val table2 = util.tableEnv.sqlQuery(sql2)
    val optimized1 = util.getPlanner.optimize(TableTestUtil.toRelNode(table1))
    val optimized2 = util.getPlanner.optimize(TableTestUtil.toRelNode(table2))
    assertThat(FlinkRelOptUtil.toString(optimized2)).isEqualTo(FlinkRelOptUtil.toString(optimized1))
  }

  /** All aggregates must be computed on the same window. */
  @Test
  def testMultiWindow(): Unit = {
    val sqlQuery =
      """
        |SELECT c,
        |    COUNT(a) OVER (PARTITION BY c ORDER BY proctime RANGE UNBOUNDED PRECEDING),
        |    SUM(a) OVER (PARTITION BY b ORDER BY proctime RANGE UNBOUNDED PRECEDING)
        |from MyTable
      """.stripMargin

    assertThatExceptionOfType(classOf[TableException])
      .isThrownBy(() => util.verifyExecPlan(sqlQuery))
  }

  /** OVER clause is necessary for [[OverAgg0]] window function. */
  @Test
  def testInvalidOverAggregation(): Unit = {
    util.addTemporarySystemFunction("overAgg", new OverAgg0)
    assertThatExceptionOfType(classOf[ValidationException])
      .isThrownBy(() => util.verifyExecPlan("SELECT overAgg(c, a) FROM MyTable"))
  }

  /** OVER clause is necessary for [[OverAgg0]] window function. */
  @Test
  def testInvalidOverAggregation2(): Unit = {
    util.addTemporarySystemFunction("overAgg", new OverAgg0)
    assertThatExceptionOfType(classOf[ValidationException])
      .isThrownBy(() => util.verifyExecPlan("SELECT overAgg(c, a) FROM MyTable"))
  }

  @Test
  def testProctimeBoundedDistinctWithNonDistinctPartitionedRowOver(): Unit = {
    val sqlQuery1 =
      """
        |SELECT b,
        |    COUNT(a) OVER (PARTITION BY b ORDER BY proctime
        |        ROWS BETWEEN 2 PRECEDING AND CURRENT ROW) AS cnt1,
        |    SUM(a) OVER (PARTITION BY b ORDER BY proctime
        |        ROWS BETWEEN 2 PRECEDING AND CURRENT ROW) AS sum1,
        |    COUNT(DISTINCT a) OVER (PARTITION BY b ORDER BY proctime
        |        ROWS BETWEEN 2 preceding AND CURRENT ROW) AS cnt2,
        |    sum(DISTINCT c) OVER (PARTITION BY b ORDER BY proctime
        |        ROWS BETWEEN 2 preceding AND CURRENT ROW) AS sum2
        |FROM MyTable
      """.stripMargin

    val sqlQuery2 =
      """
        |SELECT b,
        |    COUNT(a) OVER w AS cnt1,
        |    SUM(a) OVER w AS sum1,
        |    COUNT(DISTINCT a) OVER w AS cnt2,
        |    SUM(DISTINCT c) OVER w AS sum2
        |FROM MyTable
        |    WINDOW w AS (PARTITION BY b ORDER BY proctime ROWS BETWEEN 2 PRECEDING AND CURRENT ROW)
      """.stripMargin

    verifyPlanIdentical(sqlQuery1, sqlQuery2)
    util.verifyExecPlan(sqlQuery1)
  }

  @Test
  def testProctimeBoundedDistinctPartitionedRowOver(): Unit = {
    val sqlQuery1 =
      """
        |SELECT c,
        |    COUNT(DISTINCT a) OVER (PARTITION BY c ORDER BY proctime
        |        ROWS BETWEEN 2 PRECEDING AND CURRENT ROW) AS cnt1,
        |    SUM(DISTINCT a) OVER (PARTITION BY c ORDER BY proctime
        |        ROWS BETWEEN 2 PRECEDING AND CURRENT ROW) AS sum1
        |FROM MyTable
      """.stripMargin

    val sqlQuery2 =
      """
        |SELECT c,
        |    COUNT(DISTINCT a) OVER w AS cnt1,
        |    SUM(DISTINCT a) OVER (PARTITION BY c ORDER BY proctime
        |        ROWS BETWEEN 2 PRECEDING AND CURRENT ROW) AS sum1
        |FROM MyTable
        |    WINDOW w AS (PARTITION BY c ORDER BY proctime ROWS BETWEEN 2 PRECEDING AND CURRENT ROW)
      """.stripMargin

    verifyPlanIdentical(sqlQuery1, sqlQuery2)
    util.verifyExecPlan(sqlQuery1)
  }

  @Test
  def testProcTimeBoundedPartitionedRowsOver(): Unit = {
    val sqlQuery1 =
      """
        |SELECT c,
        |    COUNT(a) OVER (PARTITION BY c ORDER BY proctime
        |        ROWS BETWEEN 2 PRECEDING AND CURRENT ROW) AS cnt1,
        |    SUM(a) OVER (PARTITION BY c ORDER BY proctime
        |        ROWS BETWEEN 2 PRECEDING AND CURRENT ROW) AS sum1
        |FROM MyTable
      """.stripMargin

    val sqlQuery2 =
      """
        |SELECT c,
        |    COUNT(a) OVER (PARTITION BY c ORDER BY proctime
        |        ROWS BETWEEN 2 PRECEDING AND CURRENT ROW) AS cnt1,
        |    SUM(a) OVER w AS sum1
        |FROM MyTable
        |    WINDOW w AS (PARTITION BY c ORDER BY proctime ROWS BETWEEN 2 PRECEDING AND CURRENT ROW)
      """.stripMargin

    verifyPlanIdentical(sqlQuery1, sqlQuery2)
    util.verifyExecPlan(sqlQuery1)
  }

  @Test
  def testProcTimeBoundedPartitionedRangeOver(): Unit = {
    val sqlQuery1 =
      """
        |SELECT a,
        |    AVG(c) OVER (PARTITION BY a ORDER BY proctime
        |        RANGE BETWEEN INTERVAL '2' HOUR PRECEDING AND CURRENT ROW) AS avgA
        |FROM MyTable
      """.stripMargin

    val sqlQuery2 =
      """
        |SELECT a,  AVG(c) OVER w AS avgA FROM MyTable WINDOW w AS (
        |    PARTITION BY a ORDER BY proctime
        |        RANGE BETWEEN INTERVAL '2' HOUR PRECEDING AND CURRENT ROW)
      """.stripMargin

    verifyPlanIdentical(sqlQuery1, sqlQuery2)
    util.verifyExecPlan(sqlQuery1)
  }

  @Test
  def testProcTimeBoundedNonPartitionedRangeOver(): Unit = {
    val sqlQuery1 =
      """
        |SELECT a,
        |    COUNT(c) OVER (ORDER BY proctime
        |        RANGE BETWEEN INTERVAL '10' SECOND PRECEDING AND CURRENT ROW)
        | FROM MyTable
      """.stripMargin

    val sqlQuery2 =
      """
        |SELECT a, COUNT(c) OVER w FROM MyTable WINDOW w AS (
        |    ORDER BY proctime RANGE BETWEEN INTERVAL '10' SECOND PRECEDING AND CURRENT ROW)
      """.stripMargin

    verifyPlanIdentical(sqlQuery1, sqlQuery2)
    util.verifyExecPlan(sqlQuery1)
  }

  @Test
  def testProcTimeBoundedNonPartitionedRowsOver(): Unit = {
    val sqlQuery1 =
      """
        |SELECT c,
        |    COUNT(a) OVER (ORDER BY proctime ROWS BETWEEN 2 PRECEDING AND CURRENT ROW)
        |FROM MyTable
      """.stripMargin

    val sqlQuery2 =
      """
        |SELECT c, COUNT(a) OVER w FROM MyTable WINDOW w AS (
        |    ORDER BY proctime ROWS BETWEEN 2 PRECEDING AND CURRENT ROW)
      """.stripMargin

    verifyPlanIdentical(sqlQuery1, sqlQuery2)
    util.verifyExecPlan(sqlQuery1)
  }

  @Test
  def testProcTimeUnboundedPartitionedRangeOver(): Unit = {
    val sql =
      """
        |SELECT c,
        |    COUNT(a) OVER (PARTITION BY c ORDER BY proctime RANGE UNBOUNDED PRECEDING) AS cnt1,
        |    SUM(a) OVER (PARTITION BY c ORDER BY proctime RANGE UNBOUNDED PRECEDING) AS cnt2
        |FROM MyTable
      """.stripMargin

    val sql2 =
      """
        |SELECT c,
        |    COUNT(a) OVER w AS cnt1,
        |    SUM(a) OVER (PARTITION BY c ORDER BY proctime RANGE UNBOUNDED PRECEDING) AS cnt2
        |FROM MyTable WINDOW w AS (
        |    PARTITION BY c ORDER BY proctime RANGE UNBOUNDED PRECEDING)
      """.stripMargin

    verifyPlanIdentical(sql, sql2)
    util.verifyExecPlan(sql)
  }

  @Test
  def testProcTimeUnboundedPartitionedRowsOver(): Unit = {
    val sql =
      """
        |SELECT c,
        |    COUNT(a) OVER (PARTITION BY c ORDER BY proctime
        |        ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
        |FROM MyTable
      """.stripMargin

    val sql2 =
      """
        |SELECT c, COUNT(a) OVER w FROM MyTable WINDOW w AS (
        |    PARTITION BY c ORDER BY proctime ROWS UNBOUNDED PRECEDING)
      """.stripMargin

    verifyPlanIdentical(sql, sql2)
    util.verifyExecPlan(sql)
  }

  @Test
  def testProcTimeUnboundedNonPartitionedRangeOver(): Unit = {
    val sql =
      """
        |SELECT c,
        |    COUNT(a) OVER (ORDER BY proctime RANGE UNBOUNDED PRECEDING) AS cnt1,
        |    SUM(a) OVER (ORDER BY proctime RANGE UNBOUNDED PRECEDING) AS cnt2
        |FROM MyTable
      """.stripMargin

    val sql2 =
      """
        |SELECT c,
        |    COUNT(a) OVER (ORDER BY proctime RANGE UNBOUNDED PRECEDING) AS cnt1,
        |    sum(a) OVER w AS cnt2
        |FROM MyTable WINDOW w AS(
        |    ORDER BY proctime RANGE UNBOUNDED PRECEDING)
      """.stripMargin

    verifyPlanIdentical(sql, sql2)
    util.verifyExecPlan(sql)
  }

  @Test
  def testProcTimeUnboundedNonPartitionedRowsOver(): Unit = {
    val sql =
      """
        |SELECT c,
        |    COUNT(a) OVER (ORDER BY proctime ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
        |FROM MyTable
      """.stripMargin

    val sql2 =
      """
        |SELECT c, COUNT(a) OVER w FROM MyTable WINDOW w AS (
        |    ORDER BY proctime ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
      """.stripMargin

    verifyPlanIdentical(sql, sql2)
    util.verifyExecPlan(sql)
  }

  @Test
  def testRowTimeBoundedPartitionedRowsOver(): Unit = {
    val sql =
      """
        |SELECT c,
        |    COUNT(a) OVER (PARTITION BY c ORDER BY rowtime
        |        ROWS BETWEEN 5 preceding AND CURRENT ROW)
        |FROM MyTable
      """.stripMargin

    util.verifyExecPlan(sql)
  }

  @Test
  def testRowTimeBoundedPartitionedRangeOver(): Unit = {
    val sql =
      """
        |SELECT c,
        |    COUNT(a) OVER (PARTITION BY c ORDER BY rowtime
        |        RANGE BETWEEN INTERVAL '1' SECOND  PRECEDING AND CURRENT ROW)
        |    FROM MyTable
      """.stripMargin

    util.verifyExecPlan(sql)
  }

  @Test
  def testRowTimeBoundedNonPartitionedRowsOver(): Unit = {
    val sql =
      """
        |SELECT c,
        |    COUNT(a) OVER (ORDER BY rowtime ROWS BETWEEN 5 PRECEDING AND CURRENT ROW)
        |FROM MyTable
      """.stripMargin

    util.verifyExecPlan(sql)
  }

  @Test
  def testRowTimeBoundedNonPartitionedRangeOver(): Unit = {
    val sql =
      """
        |SELECT c,
        |    COUNT(a) OVER (ORDER BY rowtime
        |        RANGE BETWEEN INTERVAL '1' SECOND  PRECEDING AND CURRENT ROW) AS cnt1
        |FROM MyTable
      """.stripMargin

    util.verifyExecPlan(sql)
  }

  @Test
  def testRowTimeUnboundedPartitionedRangeOver(): Unit = {
    val sql =
      """
        |SELECT c,
        |    COUNT(a) OVER (PARTITION BY c ORDER BY rowtime RANGE UNBOUNDED PRECEDING) AS cnt1,
        |    SUM(a) OVER (PARTITION BY c ORDER BY rowtime RANGE UNBOUNDED PRECEDING) AS cnt2
        |FROM MyTable
      """.stripMargin

    util.verifyExecPlan(sql)
  }

  @Test
  def testRowTimeUnboundedPartitionedRowsOver(): Unit = {
    val sql =
      """
        |SELECT c,
        |    COUNT(a) OVER (PARTITION BY c ORDER BY rowtime ROWS UNBOUNDED PRECEDING) AS cnt1,
        |    SUM(a) OVER (PARTITION BY c ORDER BY rowtime ROWS UNBOUNDED PRECEDING) AS cnt2
        |FROM MyTable
      """.stripMargin

    util.verifyExecPlan(sql)
  }

  @Test
  def testRowTimeUnboundedNonPartitionedRangeOver(): Unit = {
    val sql =
      """
        |SELECT c,
        |    COUNT(a) OVER (ORDER BY rowtime RANGE UNBOUNDED PRECEDING) AS cnt1,
        |    SUM(a) OVER (ORDER BY rowtime RANGE UNBOUNDED PRECEDING) AS cnt2
        |FROM MyTable
      """.stripMargin

    util.verifyExecPlan(sql)
  }

  @Test
  def testRowTimeUnboundedNonPartitionedRowsOver(): Unit = {
    val sql =
      """
        |SELECT c,
        |    COUNT(a) OVER (ORDER BY rowtime ROWS UNBOUNDED PRECEDING) AS cnt1,
        |    SUM(a) OVER (ORDER BY rowtime ROWS UNBOUNDED preceding) AS cnt2
        |FROM MyTable
      """.stripMargin

    util.verifyExecPlan(sql)
  }

  @Test
  def testProcTimeBoundedPartitionedRowsOverDifferentWindows(): Unit = {
    val sql =
      """
        |SELECT a,
        |    SUM(c) OVER (PARTITION BY a ORDER BY proctime
        |       ROWS BETWEEN 3 PRECEDING AND CURRENT ROW),
        |    MIN(c) OVER (PARTITION BY a ORDER BY proctime
        |       ROWS BETWEEN 2 PRECEDING AND CURRENT ROW)
        |FROM MyTable
      """.stripMargin

    val sql2 = "SELECT " +
      "a, " +
      "SUM(c) OVER w1, " +
      "MIN(c) OVER w2 " +
      "FROM MyTable " +
      "WINDOW w1 AS (PARTITION BY a ORDER BY proctime ROWS BETWEEN 3 PRECEDING AND CURRENT ROW)," +
      "w2 AS (PARTITION BY a ORDER BY proctime ROWS BETWEEN 2 PRECEDING AND CURRENT ROW)"

    assertThatExceptionOfType(classOf[TableException])
      .isThrownBy(
        () => {
          verifyPlanIdentical(sql, sql2)
          util.verifyExecPlan(sql)
        })
  }

  @Test
  def testProcTimeBoundedPartitionedRowsOverWithBuiltinProctime(): Unit = {
    val sqlQuery = "SELECT a, " +
      "  SUM(c) OVER (" +
      "    PARTITION BY a ORDER BY proctime() ROWS BETWEEN 4 PRECEDING AND CURRENT ROW), " +
      "  MIN(c) OVER (" +
      "    PARTITION BY a ORDER BY proctime() ROWS BETWEEN 4 PRECEDING AND CURRENT ROW) " +
      "FROM MyTable"

    util.verifyExecPlan(sqlQuery)
  }

  @Test
  def testNestedOverAgg(): Unit = {
    util.addTable(s"""
                     |CREATE TEMPORARY TABLE src (
                     |  a STRING,
                     |  b STRING,
                     |  ts TIMESTAMP_LTZ(3),
                     |  watermark FOR ts as ts
                     |) WITH (
                     |  'connector' = 'values'
                     |)
                     |""".stripMargin)

    util.verifyExecPlan(s"""
                           |SELECT *
                           |FROM (
                           | SELECT
                           |    *, count(*) OVER (PARTITION BY a ORDER BY ts) AS c2
                           |  FROM (
                           |    SELECT
                           |      *, count(*) OVER (PARTITION BY a,b ORDER BY ts) AS c1
                           |    FROM src
                           |  )
                           |)
                           |""".stripMargin)
  }

  @Test
  def testWithoutOrderByClause(): Unit = {
    val sql =
      """
        |SELECT c,
        |    COUNT(a) OVER (PARTITION BY c) AS cnt1
        |FROM MyTable
      """.stripMargin

    util.verifyExecPlan(sql)
  }

  @Test
  def testWindowBoundaryNotNumeric(): Unit = {
    val sql =
      """
        |SELECT c,
        |    COUNT(a) OVER (PARTITION BY b ORDER BY proctime
        |        ROWS BETWEEN '2' PRECEDING AND CURRENT ROW) AS cnt1
        |FROM MyTable
      """.stripMargin

    assertThatThrownBy(() => util.verifyExecPlan(sql))
      .hasRootCauseMessage("CHARACTER type is not allowed for window boundary")
      .hasRootCauseInstanceOf(classOf[ValidationException])
  }

  @ParameterizedTest
  @ValueSource(strings = Array[String]("2 + 3", "power(2, 4)"))
  def testWindowBoundaryWithSimplifiableExpressions(expr: String): Unit = {
    val sql =
      s"""
         |SELECT c,
         |    COUNT(a) OVER (PARTITION BY b ORDER BY proctime
         |        ROWS BETWEEN $expr PRECEDING AND CURRENT ROW) AS cnt1
         |FROM MyTable
      """.stripMargin

    util.verifyExecPlan(sql)
  }
}
