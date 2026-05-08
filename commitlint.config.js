module.exports = {
  extends: ['@commitlint/config-conventional'],
  rules: {
    'type-case': [2, 'always', 'lower-case'],
    'subject-case': [2, 'always', 'lower-case'],
    'body-max-line-length': [0],
    'footer-max-line-length': [0],
  },
}
