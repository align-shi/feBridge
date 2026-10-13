import { useErrorState } from './ErrorNotice'
import { useEffect, useState } from 'react'
import { modelApi, errorMessage } from './api'
import type { ModelDefinition, Provider } from './api'

export default function ModelSelect({value,onChange,required=false}:{value:string;onChange:(id:string)=>void;required?:boolean}) {
  const [models,setModels]=useState<ModelDefinition[]>([])
  const [providers,setProviders]=useState<Provider[]>([])
  const [error, setError] = useErrorState()
  useEffect(() => {
    const controller=new AbortController()
    Promise.all([modelApi.models(controller.signal),modelApi.providers(controller.signal)]).then(([m,p]) => {
      setModels(m);setProviders(p)
    }).catch(e => { if(!controller.signal.aborted) setError(errorMessage(e)) })
    return () => controller.abort()
  },[])
  const available=models.filter(m => m.enabled && providers.some(p => p.id===m.providerId && p.enabled && p.keyConfigured))
  return <><label>调用模型{required && <span className="required"> *</span>}<select value={value} onChange={e => onChange(e.target.value)} required={required}>
    <option value="">请选择模型</option>
    {available.map(m => <option key={m.id} value={m.id}>{m.displayName} · {providers.find(p=>p.id===m.providerId)?.name} / {m.modelCode}</option>)}
    {value && !available.some(m=>m.id===value) && <option value={value}>当前模型不可用，请重新选择</option>}
  </select></label>{error && <div className="error" role="alert">{error}</div>}{!error && available.length===0 && <p className="helper">请先到“模型管理”配置提供商和启用模型。</p>}</>
}
