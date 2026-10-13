export default function LoginVisual() {
  return <div className="login-visual" aria-hidden="true">
    <div className="login-orbit-glow" />
    <svg viewBox="0 0 600 300" fill="none" className="login-network">
      <defs>
        <linearGradient id="login-line" x1="70" y1="280" x2="520" y2="30" gradientUnits="userSpaceOnUse">
          <stop stopColor="#55edca" stopOpacity=".05" /><stop offset=".5" stopColor="#55edca" stopOpacity=".7" /><stop offset="1" stopColor="#72a8ff" stopOpacity=".1" />
        </linearGradient>
        <radialGradient id="login-core"><stop stopColor="#a8ffea" /><stop offset=".45" stopColor="#4ee6be" /><stop offset="1" stopColor="#36a998" stopOpacity=".1" /></radialGradient>
        <filter id="login-glow"><feGaussianBlur stdDeviation="5" /></filter>
      </defs>
      <g stroke="url(#login-line)" strokeWidth="1">
        <path d="M55 182 144 90 263 38 430 55 551 142 474 250 302 280 150 246 55 182Z" />
        <path d="m144 90 158 61 128-96M263 38l39 113 249-9M55 182l247-31 172 99M150 246l152-95v129" />
        <path d="m144 90 6 156 280-191 44 195L263 38 150 246M55 182l375-127 121 87-401 104" opacity=".38" />
      </g>
      <g className="login-orbit-a" stroke="#67e9ca">
        <ellipse cx="302" cy="151" rx="106" ry="70" strokeOpacity=".15" />
        <ellipse cx="302" cy="151" rx="106" ry="70" strokeDasharray="72 470" strokeOpacity=".8" />
      </g>
      <g className="login-orbit-b" stroke="#78b5e8">
        <ellipse cx="302" cy="151" rx="106" ry="70" transform="rotate(60 302 151)" strokeOpacity=".22" />
        <ellipse cx="302" cy="151" rx="106" ry="70" transform="rotate(-60 302 151)" strokeOpacity=".22" />
      </g>
      <circle cx="302" cy="151" r="47" fill="url(#login-core)" opacity=".22" filter="url(#login-glow)" />
      <circle cx="302" cy="151" r="30" fill="#112e31" stroke="#56dbbf" strokeOpacity=".7" />
      <path d="M287 160v-18l15-8 15 8v18l-15 8-15-8Zm0-18 15 9 15-9m-15 9v17" stroke="#93f5d8" strokeWidth="1.6" strokeLinejoin="round" />
      {[ [55,182], [144,90], [263,38], [430,55], [551,142], [474,250], [302,280], [150,246] ].map(([x,y],i) =>
        <g key={i} className="login-network-node" style={{ animationDelay: -(i * .7) + 's' }}>
          <circle cx={x} cy={y} r="9" fill="#55edca" fillOpacity=".07" />
          <circle cx={x} cy={y} r="3" fill={i % 3 === 0 ? '#7eb9ee' : '#74eccd'} />
        </g>)}
      <circle r="3" fill="#a4ffe5" className="login-travel-dot"><animateMotion dur="8s" repeatCount="indefinite" path="M55 182 144 90 302 151 474 250 302 280 150 246 55 182" /></circle>
    </svg>
    <div className="login-node-label node-agent"><span>◈</span> AGENT</div>
    <div className="login-node-label node-skill"><span>✧</span> SKILLS</div>
    <div className="login-node-label node-mcp"><span>⌘</span> MCP</div>
  </div>
}
