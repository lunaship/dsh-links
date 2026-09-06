import { test } from "node:test"
import assert from "node:assert/strict"
import { normalizeQuestions, validateAnswers, MAX_CUSTOM_CHARS } from "../src/question-answers.js"

const sample = [
  {
    id: "q1",
    header: "范围",
    question: "用哪套方案？",
    options: [{ id: "a", label: "方案 A" }, { id: "b", label: "方案 B" }],
  },
  {
    id: "q2",
    question: "补充说明",
  },
  {
    id: "q3",
    question: "多选",
    multiple: true,
    options: ["x", "y"],
  },
]

test("逐题答案与题目 ID/选项对应", () => {
  const { questions } = normalizeQuestions(sample)
  const result = validateAnswers(questions, {
    answers: [
      { id: "q1", selected: ["a"] },
      { id: "q2", selected: [], custom: "自定义" },
      { id: "q3", selected: ["x", "y"] },
    ],
  })
  assert.equal(result.ok, true)
  assert.deepEqual(result.answer.answers[0], { id: "q1", selected: ["a"] })
  assert.equal(result.answer.answers[1].custom, "自定义")
})

test("伪造题目、重复答案、无效选项、缺必填、超长均拒绝", () => {
  const { questions } = normalizeQuestions(sample)
  assert.equal(validateAnswers(questions, { answers: [{ id: "nope", selected: ["a"] }] }).code, "answer-count")
  assert.equal(validateAnswers(questions, {
    answers: [
      { id: "q1", selected: ["a"] },
      { id: "q1", selected: ["a"] },
      { id: "q3", selected: ["x"] },
    ],
  }).ok, false)
  assert.equal(validateAnswers(questions, {
    answers: [
      { id: "q1", selected: ["zzz"] },
      { id: "q2", custom: "x" },
      { id: "q3", selected: ["x"] },
    ],
  }).code, "invalid-option")
  assert.equal(validateAnswers(questions, {
    answers: [
      { id: "q1", selected: [] },
      { id: "q2", custom: "x" },
      { id: "q3", selected: ["x"] },
    ],
  }).code, "required")
  assert.equal(validateAnswers(questions, {
    answers: [
      { id: "q1", selected: ["a"] },
      { id: "q2", custom: "x".repeat(MAX_CUSTOM_CHARS + 1) },
      { id: "q3", selected: ["x"] },
    ],
  }).code, "custom-too-long")
})

test("未声明可跳过时不得提交空答案", () => {
  const { questions } = normalizeQuestions([{ id: "q1", question: "必填" }])
  assert.equal(validateAnswers(questions, { answers: [{ id: "q1", selected: [] }] }).code, "required")
})

test("不支持的题型保持可处理并拒绝猜测", () => {
  const { questions } = normalizeQuestions([{ id: "q1", type: "rank", question: "排序" }])
  assert.equal(questions[0].kind, "unsupported")
  assert.equal(validateAnswers(questions, { answers: [{ id: "q1", selected: ["a"] }] }).code, "unsupported-question")
})
