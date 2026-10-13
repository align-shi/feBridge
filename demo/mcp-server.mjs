import http from 'node:http'

const tools = [{
  name: 'query_customer',
  description: '按客户编号查询演示客户资料。仅返回虚构数据，只读。',
  inputSchema: {
    type: 'object',
    properties: { customerId: { type: 'string', enum: ['C001', 'C002'] } },
    required: ['customerId'],
    additionalProperties: false,
  },
}]
const customers = {
  C001: { customerId: 'C001', name: '枫桥演示客户一', level: 'VIP', status: '合作中' },
  C002: { customerId: 'C002', name: '枫桥演示客户二', level: '普通', status: '待跟进' },
}
const server = http.createServer(async (req, res) => {
  if (req.url !== '/mcp') { res.writeHead(404).end(); return }
  if (req.method !== 'POST') { res.writeHead(405).end(); return }
  if (process.env.DEMO_MCP_TOKEN && req.headers.authorization !== 'Bearer ' + process.env.DEMO_MCP_TOKEN) {
    res.writeHead(401).end(); return
  }
  let raw = ''
  for await (const chunk of req) {
    raw += chunk
    if (raw.length > 65536) { res.writeHead(413).end(); return }
  }
  let request
  try { request = JSON.parse(raw) } catch { res.writeHead(400).end(); return }
  if (request.id === undefined) { res.writeHead(202).end(); return }
  let result
  switch (request.method) {
    case 'initialize':
      result = { protocolVersion: request.params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: 'febridge-demo', version: '1.0.0' } }
      break
    case 'ping': result = {}; break
    case 'tools/list': result = { tools }; break
    case 'tools/call': {
      const args = request.params?.arguments
      const customer = request.params?.name === 'query_customer' && args && Object.keys(args).length === 1 ? customers[args.customerId] : null
      result = { content: [{ type: 'text', text: JSON.stringify(customer || { error: '客户不存在或参数无效' }) }], isError: !customer }
      break
    }
    default:
      res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ jsonrpc: '2.0', id: request.id, error: { code: -32601, message: 'Method not found' } }))
      return
  }
  res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ jsonrpc: '2.0', id: request.id, result }))
})
server.listen(8090, '127.0.0.1', () => console.log('演示 MCP：http://127.0.0.1:8090/mcp；客户编号 C001 / C002'))
